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
import org.joml.Vector3d;
import org.weasis.dicom.codec.seg.SegmentationVolume;

/**
 * The rendered segmentation as a {@link SegmentField}, read as the shaders read it: with the label
 * voxels, the storage ID of the nearest label voxel ({@code segVoxel()}, the segmentation texture
 * being uploaded last slice first like the volume); with the smooth surfaces, the trilinear
 * smoothed occupancy of the visible segments at or above one half ({@code segSmoothOccupancy()},
 * the occupancy slices being the ones {@link SegOccupancy} uploads, encoded here from the same
 * labels and visibility). The opacity is the colour LUT's alpha of the nearest visible label, or of
 * the nearest visible one among the eight label voxels around the point for a smoothed surface
 * point whose nearest label lies outside, as {@code segSurfaceColor()} does.
 *
 * <p>Slices are read on demand into small caches; one instance per operation, not thread-safe.
 */
public final class SegVolumeField implements SegmentField {

  /** Label slices of a segmentation, in the segmentation's own slice order. */
  public interface LabelSource {
    int sizeX();

    int sizeY();

    int sizeZ();

    /** Storage IDs of slice {@code z}, row-major, or {@code null} when unavailable. */
    int[] slice(int z);
  }

  private static final int SLICE_CACHE = 32;

  private final LabelSource labels;
  private final float[] alpha;
  private final boolean[] visible;
  private final boolean smooth;
  private final Map<Integer, int[]> labelSlices = lru();
  private final Map<Integer, byte[]> occupancySlices = lru();

  /**
   * @param colorLut the segment colour LUT as uploaded, RGBA bytes by storage ID
   * @param smooth whether the renderer draws the smooth surfaces (its occupancy texture is ready
   *     for this very visibility), as the {@code segSmooth} uniform
   */
  public SegVolumeField(LabelSource labels, byte[] colorLut, boolean smooth) {
    this.labels = labels;
    this.visible = SegOccupancy.visibleIds(colorLut);
    this.alpha = new float[visible.length];
    for (int id = 1; id < alpha.length; id++) {
      alpha[id] = (colorLut[id * 4 + 3] & 0xFF) / 255f;
    }
    this.smooth = smooth;
  }

  public SegVolumeField(SegmentationVolume volume, byte[] colorLut, boolean smooth) {
    this(labelsOf(volume), colorLut, smooth);
  }

  private static LabelSource labelsOf(SegmentationVolume volume) {
    return new LabelSource() {
      @Override
      public int sizeX() {
        return volume.getSizeX();
      }

      @Override
      public int sizeY() {
        return volume.getSizeY();
      }

      @Override
      public int sizeZ() {
        return volume.getSizeZ();
      }

      @Override
      public int[] slice(int z) {
        return volume.isDisposed() ? null : volume.exportSliceBitmask(z);
      }
    };
  }

  public boolean isSmooth() {
    return smooth;
  }

  /**
   * The label and occupancy slice caches at their ceiling plus the scratch of encoding one
   * occupancy slice: the inside mask, the two squared-distance grids and the row buffers.
   */
  @Override
  public long workingBytes() {
    long n = (long) labels.sizeX() * labels.sizeY();
    long caches = SLICE_CACHE * (Integer.BYTES + 1) * n;
    return caches + encodeScratchBytes(labels.sizeX(), labels.sizeY());
  }

  /** The scratch of {@link SegOccupancy#encodeSlice}, as it allocates for one slice. */
  static long encodeScratchBytes(int width, int height) {
    long n = (long) width * height;
    long rows = Math.max(width, height) + 1L;
    return n /* inside */
        + n /* out */
        + 2L * Float.BYTES * n /* two squared-distance grids */
        + rows * (3L * Float.BYTES + Integer.BYTES);
  }

  @Override
  public double opacity(Vector3d t) {
    int sx = labels.sizeX();
    int sy = labels.sizeY();
    int sz = labels.sizeZ();
    // segVoxel(): round(texCoord * segSize - 0.5), with z reversed by the upload.
    int x = (int) Math.round(t.x * sx - 0.5);
    int y = (int) Math.round(t.y * sy - 0.5);
    int texZ = (int) Math.round(t.z * sz - 0.5);
    if (smooth) {
      if (occupancy(t) < 0.5) {
        return 0;
      }
      double a = alphaAt(x, y, texZ);
      return a > 0 ? a : nearestVisibleAlpha(t);
    }
    return alphaAt(x, y, texZ);
  }

  @Override
  public int visibleLabel(Vector3d t) {
    int sx = labels.sizeX();
    int sy = labels.sizeY();
    int sz = labels.sizeZ();
    int x = (int) Math.round(t.x * sx - 0.5);
    int y = (int) Math.round(t.y * sy - 0.5);
    int texZ = (int) Math.round(t.z * sz - 0.5);
    int id = visibleLabelAt(x, y, texZ);
    if (id > 0) {
      return id;
    }
    double px = t.x * sx - 0.5;
    double py = t.y * sy - 0.5;
    double pz = t.z * sz - 0.5;
    int bx = (int) Math.floor(px);
    int by = (int) Math.floor(py);
    int bz = (int) Math.floor(pz);
    double best = 4.0;
    for (int i = 0; i < 8; i++) {
      int vx = bx + (i & 1);
      int vy = by + ((i >> 1) & 1);
      int vz = bz + ((i >> 2) & 1);
      int label = visibleLabelAt(vx, vy, vz);
      if (label > 0) {
        double dx = vx - px;
        double dy = vy - py;
        double dz = vz - pz;
        double dist = dx * dx + dy * dy + dz * dz;
        if (dist < best) {
          best = dist;
          id = label;
        }
      }
    }
    return id;
  }

  /** The label at a texture voxel when it is visible, 0 outside or when hidden. */
  private int visibleLabelAt(int x, int y, int texZ) {
    if (alphaAt(x, y, texZ) <= 0) {
      return 0;
    }
    return labelSlice(labels.sizeZ() - 1 - texZ)[y * labels.sizeX() + x];
  }

  /** The LUT alpha of the label at a texture voxel, 0 outside or when hidden. */
  private double alphaAt(int x, int y, int texZ) {
    int sx = labels.sizeX();
    int sy = labels.sizeY();
    int sz = labels.sizeZ();
    if (x < 0 || y < 0 || texZ < 0 || x >= sx || y >= sy || texZ >= sz) {
      return 0;
    }
    int[] slice = labelSlice(sz - 1 - texZ);
    if (slice == null) {
      return 0;
    }
    int id = slice[y * sx + x];
    return id > 0 && id < alpha.length ? alpha[id] : 0;
  }

  /**
   * The voxels of a grid that lie in a visible segment of the storage IDs {@code ids}, or touch
   * one: the nearest label voxel of the grid voxel's centre, or one of its six face neighbours,
   * holds one of the IDs. The one-voxel margin takes the blurred edge of the bone with it, which
   * the renderer would otherwise still draw as a thin shell. Hidden segments hold no voxel. Reads
   * the label slices in order and skips the grid slices near none of the IDs.
   *
   * @param ids table by storage ID of the IDs wanted
   */
  public VoxelBits regionOf(VolumeSpace space, boolean[] ids) {
    int gx = space.grid().x;
    int gy = space.grid().y;
    int gz = space.grid().z;
    int sx = labels.sizeX();
    int sy = labels.sizeY();
    int sz = labels.sizeZ();
    VoxelBits region = new VoxelBits(gx, gy, gz);
    // Texture coordinates are separable, (i + 0.5) / size along each axis, and the nearest label
    // voxel is round(texCoord * labelSize - 0.5) as in segVoxel().
    int[] nx = new int[gx];
    for (int x = 0; x < gx; x++) {
      nx[x] = (int) Math.round((x + 0.5) / gx * sx - 0.5);
    }
    int[] ny = new int[gy];
    for (int y = 0; y < gy; y++) {
      ny[y] = (int) Math.round((y + 0.5) / gy * sy - 0.5);
    }
    Boolean[] holds = new Boolean[sz];
    for (int z = 0; z < gz; z++) {
      int nz = (int) Math.round((z + 0.5) / gz * sz - 0.5);
      if (!holdsAny(nz - 1, ids, holds)
          && !holdsAny(nz, ids, holds)
          && !holdsAny(nz + 1, ids, holds)) {
        continue;
      }
      int[] below = sliceOfTexZ(nz - 1);
      int[] at = sliceOfTexZ(nz);
      int[] above = sliceOfTexZ(nz + 1);
      for (int y = 0; y < gy; y++) {
        int ly = ny[y];
        for (int x = 0; x < gx; x++) {
          int lx = nx[x];
          if (wanted(at, lx, ly, sx, sy, ids)
              || wanted(at, lx - 1, ly, sx, sy, ids)
              || wanted(at, lx + 1, ly, sx, sy, ids)
              || wanted(at, lx, ly - 1, sx, sy, ids)
              || wanted(at, lx, ly + 1, sx, sy, ids)
              || wanted(below, lx, ly, sx, sy, ids)
              || wanted(above, lx, ly, sx, sy, ids)) {
            region.set(x, y, z);
          }
        }
      }
    }
    return region;
  }

  /** Whether a label voxel holds one of the IDs and is visible. */
  private boolean wanted(int[] slice, int x, int y, int sx, int sy, boolean[] ids) {
    int id = visibleIn(slice, x, y, sx, sy);
    return id > 0 && id < ids.length && ids[id];
  }

  /** Whether texture slice {@code texZ} holds one of the IDs; cached in {@code holds}. */
  private boolean holdsAny(int texZ, boolean[] ids, Boolean[] holds) {
    if (texZ < 0 || texZ >= holds.length) {
      return false;
    }
    if (holds[texZ] == null) {
      int[] slice = sliceOfTexZ(texZ);
      boolean any = false;
      if (slice != null) {
        for (int id : slice) {
          if (id > 0 && id < ids.length && ids[id]) {
            any = true;
            break;
          }
        }
      }
      holds[texZ] = any;
    }
    return holds[texZ];
  }

  /** The label slice drawn at texture slice {@code texZ}, {@code null} outside or unavailable. */
  private int[] sliceOfTexZ(int texZ) {
    int sz = labels.sizeZ();
    return texZ < 0 || texZ >= sz ? null : labelSlice(sz - 1 - texZ);
  }

  /** The label of a voxel of a slice when it is visible, 0 outside or when hidden. */
  private int visibleIn(int[] slice, int x, int y, int sx, int sy) {
    if (slice == null || x < 0 || y < 0 || x >= sx || y >= sy) {
      return 0;
    }
    int id = slice[y * sx + x];
    return id > 0 && id < alpha.length && alpha[id] > 0 ? id : 0;
  }

  /** segSurfaceColor(): the closest visible one among the eight label voxels around the point. */
  private double nearestVisibleAlpha(Vector3d t) {
    double px = t.x * labels.sizeX() - 0.5;
    double py = t.y * labels.sizeY() - 0.5;
    double pz = t.z * labels.sizeZ() - 0.5;
    int bx = (int) Math.floor(px);
    int by = (int) Math.floor(py);
    int bz = (int) Math.floor(pz);
    double best = 4.0;
    double result = 0;
    for (int i = 0; i < 8; i++) {
      int vx = bx + (i & 1);
      int vy = by + ((i >> 1) & 1);
      int vz = bz + ((i >> 2) & 1);
      double a = alphaAt(vx, vy, vz);
      if (a > 0) {
        double dx = vx - px;
        double dy = vy - py;
        double dz = vz - pz;
        double dist = dx * dx + dy * dy + dz * dz;
        if (dist < best) {
          best = dist;
          result = a;
        }
      }
    }
    return result;
  }

  /**
   * The smoothed occupancy at a texture coordinate as the shader samples it: trilinear over the
   * occupancy voxels, centres at {@code (i + 0.5) / size}, zero beyond the volume (the texture's
   * border colour).
   */
  public double occupancy(Vector3d t) {
    int sx = labels.sizeX();
    int sy = labels.sizeY();
    int sz = labels.sizeZ();
    double px = t.x * sx - 0.5;
    double py = t.y * sy - 0.5;
    double pz = t.z * sz - 0.5;
    int x0 = (int) Math.floor(px);
    int y0 = (int) Math.floor(py);
    int z0 = (int) Math.floor(pz);
    double fx = px - x0;
    double fy = py - y0;
    double fz = pz - z0;
    double sum = 0;
    for (int i = 0; i < 8; i++) {
      int dx = i & 1;
      int dy = (i >> 1) & 1;
      int dz = (i >> 2) & 1;
      double w = (dx == 0 ? 1 - fx : fx) * (dy == 0 ? 1 - fy : fy) * (dz == 0 ? 1 - fz : fz);
      if (w > 0) {
        sum += w * occupancyAt(x0 + dx, y0 + dy, z0 + dz);
      }
    }
    return sum;
  }

  /** Occupancy of one texture voxel in [0, 1], zero beyond the volume. */
  private double occupancyAt(int x, int y, int texZ) {
    int sx = labels.sizeX();
    int sy = labels.sizeY();
    int sz = labels.sizeZ();
    if (x < 0 || y < 0 || texZ < 0 || x >= sx || y >= sy || texZ >= sz) {
      return 0;
    }
    byte[] slice = occupancySlice(sz - 1 - texZ);
    return slice == null ? 0 : (slice[y * sx + x] & 0xFF) / 255.0;
  }

  private int[] labelSlice(int z) {
    int[] cached = labelSlices.get(z);
    if (cached == null && !labelSlices.containsKey(z)) {
      cached = labels.slice(z);
      labelSlices.put(z, cached);
    }
    return cached;
  }

  /** The occupancy slice as SegOccupancy.fill encodes it: inside where the label is visible. */
  private byte[] occupancySlice(int z) {
    byte[] cached = occupancySlices.get(z);
    if (cached == null && !occupancySlices.containsKey(z)) {
      int[] ids = labelSlice(z);
      if (ids != null) {
        boolean[] inside = new boolean[ids.length];
        for (int i = 0; i < ids.length; i++) {
          inside[i] = ids[i] > 0 && ids[i] < visible.length && visible[ids[i]];
        }
        cached = SegOccupancy.encodeSlice(inside, labels.sizeX(), labels.sizeY());
      }
      occupancySlices.put(z, cached);
    }
    return cached;
  }

  private static <V> Map<Integer, V> lru() {
    return new LinkedHashMap<>(SLICE_CACHE * 2, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest) {
        return size() > SLICE_CACHE;
      }
    };
  }
}
