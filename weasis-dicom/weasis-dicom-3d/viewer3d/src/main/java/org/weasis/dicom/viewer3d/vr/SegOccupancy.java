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

import java.nio.ByteBuffer;
import java.util.stream.IntStream;

/**
 * Builds the smoothed occupancy of the visible segments that the smooth surface rendering samples
 * with trilinear filtering: one byte per voxel, above 127 inside a visible segment and below 128
 * outside, so that the 0.5 level of the texture is the segment surface.
 *
 * <p>A plain inside/outside mask (255/0) only rounds the voxel corners: interpolated between two
 * slices, its 0.5 level stays half-way between them, and thick slices still render as terraces. The
 * value is therefore the signed distance to the segment boundary within the slice, clamped to
 * {@value #DISTANCE_RANGE} voxels: 255 deep inside, 0 far outside. Interpolating it between two
 * slices moves the surface linearly from one contour to the next (shape-based interpolation), and
 * its gradient gives smooth normals. At the voxel centres the inside test still matches the labels
 * exactly.
 *
 * <p>Visibility is taken from the segment colour LUT (a storage ID is visible when its alpha is not
 * zero), the same test the shader applies to the labels, so the texture only has to be rebuilt when
 * that set changes and never for a colour or opacity edit.
 */
public final class SegOccupancy {

  /** In-slice distance, in voxels, mapped onto the byte range; farther voxels are clamped. */
  public static final int DISTANCE_RANGE = 16;

  // Any squared distance above the clamped range is equivalent: keeps the transform in floats.
  private static final float FAR = 2f * (DISTANCE_RANGE + 1) * (DISTANCE_RANGE + 1);

  private SegOccupancy() {}

  /**
   * Returns, per storage ID, whether its RGBA8 colour LUT entry shows a visible segment. Slot 0 is
   * the background and is never visible.
   */
  public static boolean[] visibleIds(byte[] colorLut) {
    if (colorLut == null) {
      return new boolean[0];
    }
    boolean[] visible = new boolean[colorLut.length / 4];
    for (int id = 1; id < visible.length; id++) {
      visible[id] = colorLut[id * 4 + 3] != 0;
    }
    return visible;
  }

  /** Returns {@code true} when at least one storage ID is visible. */
  public static boolean anyVisible(boolean[] visible) {
    for (boolean v : visible) {
      if (v) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the byte encoding a signed distance to the segment boundary, in voxels, positive
   * inside.
   */
  public static int encode(double signedDistance) {
    long value = Math.round(127.5 + signedDistance * 127.5 / DISTANCE_RANGE);
    return (int) Math.clamp(value, 0, 255);
  }

  /**
   * Writes the smoothed occupancy of {@code depth} slices of storage IDs, read from {@code labels}
   * at its position, into {@code occupancy} at its position, and advances both positions. Slices
   * are processed in parallel.
   *
   * @param labels storage IDs, one unsigned byte or one unsigned short (in the buffer's byte order)
   *     per voxel, slice after slice
   * @param shortStorage {@code true} when {@code labels} holds unsigned shorts
   * @param visible visibility per storage ID, see {@link #visibleIds(byte[])}; IDs beyond its
   *     length are not visible
   * @param occupancy destination, one byte per voxel
   */
  public static void fill(
      ByteBuffer labels,
      boolean shortStorage,
      boolean[] visible,
      ByteBuffer occupancy,
      int width,
      int height,
      int depth) {
    int sliceVoxels = width * height;
    int labelStart = labels.position();
    int occupancyStart = occupancy.position();
    int bytesPerLabel = shortStorage ? Short.BYTES : Byte.BYTES;
    IntStream.range(0, depth)
        .parallel()
        .forEach(
            z -> {
              // Duplicates: buffers are not thread-safe, even for reading.
              ByteBuffer src = labels.duplicate().order(labels.order());
              boolean[] inside = new boolean[sliceVoxels];
              int base = labelStart + z * sliceVoxels * bytesPerLabel;
              for (int i = 0; i < sliceVoxels; i++) {
                int id =
                    shortStorage
                        ? Short.toUnsignedInt(src.getShort(base + i * Short.BYTES))
                        : Byte.toUnsignedInt(src.get(base + i));
                inside[i] = id < visible.length && visible[id];
              }
              byte[] slice = encodeSlice(inside, width, height);
              occupancy.duplicate().put(occupancyStart + z * sliceVoxels, slice);
            });
    labels.position(labelStart + depth * sliceVoxels * bytesPerLabel);
    occupancy.position(occupancyStart + depth * sliceVoxels);
  }

  /** Encodes one slice given its inside mask, row after row. */
  static byte[] encodeSlice(boolean[] inside, int width, int height) {
    int n = width * height;
    byte[] out = new byte[n];
    int insideCount = 0;
    for (boolean b : inside) {
      insideCount += b ? 1 : 0;
    }
    if (insideCount == 0 || insideCount == n) {
      // No boundary in this slice: uniformly far outside or far inside.
      java.util.Arrays.fill(out, (byte) (insideCount == 0 ? 0 : 255));
      return out;
    }
    float[] toOutside = squaredDistances(inside, false, width, height);
    float[] toInside = squaredDistances(inside, true, width, height);
    for (int i = 0; i < n; i++) {
      // Voxel centres lie half a voxel from the boundary between two neighbours.
      double distance = inside[i] ? Math.sqrt(toOutside[i]) - 0.5 : 0.5 - Math.sqrt(toInside[i]);
      out[i] = (byte) encode(distance);
    }
    return out;
  }

  /**
   * Squared Euclidean distance from each voxel to the nearest voxel whose mask value is {@code
   * target}, capped at {@link #FAR} (Felzenszwalb and Huttenlocher, columns then rows).
   */
  private static float[] squaredDistances(boolean[] inside, boolean target, int width, int height) {
    float[] grid = new float[width * height];
    for (int i = 0; i < grid.length; i++) {
      grid[i] = inside[i] == target ? 0f : FAR;
    }
    int max = Math.max(width, height);
    float[] f = new float[max];
    float[] d = new float[max];
    int[] v = new int[max];
    float[] z = new float[max + 1];
    for (int x = 0; x < width; x++) {
      for (int y = 0; y < height; y++) {
        f[y] = grid[y * width + x];
      }
      transform1d(f, height, d, v, z);
      for (int y = 0; y < height; y++) {
        grid[y * width + x] = Math.min(d[y], FAR);
      }
    }
    for (int y = 0; y < height; y++) {
      System.arraycopy(grid, y * width, f, 0, width);
      transform1d(f, width, d, v, z);
      for (int x = 0; x < width; x++) {
        grid[y * width + x] = Math.min(d[x], FAR);
      }
    }
    return grid;
  }

  // Lower envelope of the parabolas rooted at each sample (1D squared distance transform).
  private static void transform1d(float[] f, int n, float[] d, int[] v, float[] z) {
    int k = 0;
    v[0] = 0;
    z[0] = Float.NEGATIVE_INFINITY;
    z[1] = Float.POSITIVE_INFINITY;
    for (int q = 1; q < n; q++) {
      float s = intersection(f, q, v[k]);
      while (s <= z[k]) {
        k--;
        s = intersection(f, q, v[k]);
      }
      k++;
      v[k] = q;
      z[k] = s;
      z[k + 1] = Float.POSITIVE_INFINITY;
    }
    k = 0;
    for (int q = 0; q < n; q++) {
      while (z[k + 1] < q) {
        k++;
      }
      float dq = q - v[k];
      d[q] = dq * dq + f[v[k]];
    }
  }

  private static float intersection(float[] f, int q, int p) {
    return ((f[q] + (float) q * q) - (f[p] + (float) p * p)) / (2f * q - 2f * p);
  }
}
