/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer3d.vr;

import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Vector3i;

/**
 * The scalar field of the volume texture reproduced on the CPU, texture voxel by texture voxel,
 * from what the upload does ({@code VolumeBuilder}): each texture slice is the source slice of
 * {@link VolumeSpace#sourceSliceOfTextureSlice}, scaled in plane to the texture's size with a
 * centre-aligned bilinear filter, and, when the volume is smoothed, filtered by the {@link
 * VolumeSmoother} over the texture slices with the same kernels and the same edge replication. So
 * the picking and the keep/remove selection see exactly the field the renderer samples, a spike
 * that the smoothing makes transparent included.
 *
 * <p>Slices are made on demand and kept in small caches, so a pick costs a few slices and a region
 * grow costs the slices of its structure, not a copy of the volume. One instance serves a view as
 * long as its texture and smoothing stay the same; the pick and cut workers share it, one at a
 * time.
 */
public final class TextureField implements VoxelSampler {

  /** Source slices of the CPU volume, in the volume's own order and units. */
  public interface SliceSource {
    int width();

    int height();

    int depth();

    /** The values of slice {@code z}, row-major, {@code width * height} of them. */
    float[] slice(int z);

    /** The shift the upload adds to make the values unsigned (32768 for a signed volume). */
    int unsignedOffset();
  }

  private static final int SOURCE_CACHE = 24;
  private static final int FILTERED_CACHE = 24;
  private static final int TEXTURE_CACHE = 48;

  private final SliceSource source;
  private final Vector3i texture;
  private final VolumeSmoother smoother;
  private final int radiusZ;
  private final Map<Integer, float[]> sourceSlices = lru(SOURCE_CACHE);
  private final Map<Integer, float[]> scaledSlices = lru(SOURCE_CACHE);
  private final Map<Integer, float[]> filteredSlices = lru(FILTERED_CACHE);
  private final Map<Integer, float[]> textureSlices = lru(TEXTURE_CACHE);
  private long slicesMade;

  /**
   * @param smoother the smoothing of the upload, or {@code null} when the volume is not smoothed
   */
  public TextureField(SliceSource source, Vector3i texture, VolumeSmoother smoother) {
    this.source = source;
    this.texture = new Vector3i(texture);
    this.smoother = smoother;
    this.radiusZ = smoother == null ? 0 : smoother.getRadiusZ();
  }

  /** The smoother of the upload for a texture, as VolumeBuilder builds it, or {@code null}. */
  public static VolumeSmoother smootherOf(
      boolean smoothing, int width, int height, double texelX, double texelZ) {
    if (!smoothing) {
      return null;
    }
    double sigmaXY = VolumeSmoother.SIGMA_XY;
    return new VolumeSmoother(
        width, height, sigmaXY, VolumeSmoother.sigmaZ(sigmaXY, texelX, texelZ));
  }

  public Vector3i texture() {
    return new Vector3i(texture);
  }

  /** Texture slices materialised so far, for the logs. */
  public synchronized long slicesMade() {
    return slicesMade;
  }

  @Override
  public long workingBytes() {
    long slice = 4L * texture.x * texture.y;
    long sourceSlice = 4L * source.width() * source.height();
    return slice * (TEXTURE_CACHE + FILTERED_CACHE + SOURCE_CACHE) + sourceSlice * SOURCE_CACHE;
  }

  @Override
  public synchronized double valueAt(int x, int y, int z) {
    if (x < 0 || y < 0 || z < 0 || x >= texture.x || y >= texture.y || z >= texture.z) {
      return Double.NaN;
    }
    return textureSlice(z)[y * texture.x + x];
  }

  @Override
  public synchronized double interpolate(double x, double y, double z) {
    return VoxelSampler.super.interpolate(x, y, z);
  }

  /** The values of texture slice {@code z}, as uploaded. */
  public synchronized float[] textureSlice(int z) {
    float[] cached = textureSlices.get(z);
    if (cached != null) {
      return cached;
    }
    float[] slice;
    if (smoother == null) {
      slice = scaledSlice(z);
    } else {
      float[][] taps = new float[2 * radiusZ + 1][];
      for (int k = -radiusZ; k <= radiusZ; k++) {
        taps[k + radiusZ] = filteredSlice(Math.clamp(z + k, 0, texture.z - 1));
      }
      short[] combined = smoother.combineTaps(taps);
      slice = new float[combined.length];
      int offset = source.unsignedOffset();
      for (int i = 0; i < combined.length; i++) {
        slice[i] = (combined[i] & 0xFFFF) - offset;
      }
    }
    textureSlices.put(z, slice);
    slicesMade++;
    return slice;
  }

  /** The in-plane filtered slice, in the unsigned units of the upload. */
  private float[] filteredSlice(int z) {
    float[] cached = filteredSlices.get(z);
    if (cached != null) {
      return cached;
    }
    float[] scaled = scaledSlice(z);
    short[] unsigned = new short[scaled.length];
    int offset = source.unsignedOffset();
    for (int i = 0; i < scaled.length; i++) {
      unsigned[i] = (short) Math.clamp(Math.round(scaled[i]) + offset, 0, 0xFFFF);
    }
    float[] filtered = smoother.filterInPlane(unsigned);
    filteredSlices.put(z, filtered);
    return filtered;
  }

  /** The source slice of texture slice {@code z}, scaled to the texture's width and height. */
  private float[] scaledSlice(int z) {
    float[] cached = scaledSlices.get(z);
    if (cached != null) {
      return cached;
    }
    int sourceZ = VolumeSpace.sourceSliceOfTextureSlice(z, texture.z, source.depth());
    float[] src = sourceSlice(sourceZ);
    float[] scaled =
        source.width() == texture.x && source.height() == texture.y
            ? src
            : resample(src, source.width(), source.height(), texture.x, texture.y);
    scaledSlices.put(z, scaled);
    return scaled;
  }

  private float[] sourceSlice(int z) {
    float[] cached = sourceSlices.get(z);
    if (cached != null) {
      return cached;
    }
    float[] slice = source.slice(z);
    if (slice.length != source.width() * source.height()) {
      throw new IllegalStateException("Slice " + z + " has " + slice.length + " values");
    }
    sourceSlices.put(z, slice);
    return slice;
  }

  /** Centre-aligned bilinear resampling, as OpenCV's INTER_LINEAR resize of the upload. */
  static float[] resample(float[] src, int sw, int sh, int dw, int dh) {
    float[] dst = new float[dw * dh];
    double sx = sw / (double) dw;
    double sy = sh / (double) dh;
    for (int y = 0; y < dh; y++) {
      double fy = Math.max((y + 0.5) * sy - 0.5, 0);
      int y0 = Math.min((int) fy, sh - 1);
      int y1 = Math.min(y0 + 1, sh - 1);
      double wy = fy - y0;
      for (int x = 0; x < dw; x++) {
        double fx = Math.max((x + 0.5) * sx - 0.5, 0);
        int x0 = Math.min((int) fx, sw - 1);
        int x1 = Math.min(x0 + 1, sw - 1);
        double wx = fx - x0;
        double top = src[y0 * sw + x0] * (1 - wx) + src[y0 * sw + x1] * wx;
        double bottom = src[y1 * sw + x0] * (1 - wx) + src[y1 * sw + x1] * wx;
        dst[y * dw + x] = (float) (top * (1 - wy) + bottom * wy);
      }
    }
    return dst;
  }

  private static <V> Map<Integer, V> lru(int size) {
    return new LinkedHashMap<>(size * 2, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest) {
        return size() > size;
      }
    };
  }
}
