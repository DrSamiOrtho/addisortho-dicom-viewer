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

import java.util.Arrays;

/**
 * The voxels one cut flips in a {@link VoxelBits} mask, run-length encoded slice by slice: for each
 * slice, pairs of (first voxel, length) in row-major slice order. Applying the same diff twice
 * restores the mask, which is what undo and redo do.
 */
public final class MaskDiff {

  private static final int[] NONE = new int[0];

  private final int[][] runs;
  private final long voxels;
  private final int zMin;
  private final int zMax;

  private MaskDiff(int[][] runs, long voxels, int zMin, int zMax) {
    this.runs = runs;
    this.voxels = voxels;
    this.zMin = zMin;
    this.zMax = zMax;
  }

  /** Collects runs slice by slice; each slice may be filled from its own thread. */
  public static final class Builder {
    private final int[][] runs;
    private final int[] lengths;

    public Builder(int sizeZ) {
      this.runs = new int[sizeZ][];
      this.lengths = new int[sizeZ];
    }

    /** Adds the run {@code [start, start + length)} of slice {@code z}; runs must not overlap. */
    public void addRun(int z, int start, int length) {
      if (length <= 0) {
        return;
      }
      int[] r = runs[z];
      int n = lengths[z];
      if (r == null) {
        r = new int[64];
        runs[z] = r;
      } else if (n + 2 > r.length) {
        r = Arrays.copyOf(r, r.length * 2);
        runs[z] = r;
      }
      r[n] = start;
      r[n + 1] = length;
      lengths[z] = n + 2;
    }

    public MaskDiff build() {
      long voxels = 0;
      int zMin = Integer.MAX_VALUE;
      int zMax = -1;
      int[][] packed = new int[runs.length][];
      for (int z = 0; z < runs.length; z++) {
        int n = lengths[z];
        if (n == 0) {
          packed[z] = NONE;
          continue;
        }
        packed[z] = Arrays.copyOf(runs[z], n);
        for (int i = 1; i < n; i += 2) {
          voxels += packed[z][i];
        }
        zMin = Math.min(zMin, z);
        zMax = Math.max(zMax, z);
      }
      return new MaskDiff(packed, voxels, zMin, zMax);
    }
  }

  /** Voxels this diff flips. */
  public long voxels() {
    return voxels;
  }

  public boolean isEmpty() {
    return voxels == 0;
  }

  /** First slice touched, or {@link Integer#MAX_VALUE} when the diff is empty. */
  public int zMin() {
    return zMin;
  }

  /** Last slice touched, or -1 when the diff is empty. */
  public int zMax() {
    return zMax;
  }

  public int sizeZ() {
    return runs.length;
  }

  /** Heap taken by the runs. */
  public long bytes() {
    long n = 0;
    for (int[] r : runs) {
      n += 4L * r.length;
    }
    return n;
  }

  /** Flips the voxels of this diff in {@code bits}, whose slices must match. */
  public void applyTo(VoxelBits bits) {
    if (bits.sizeZ() != runs.length) {
      throw new IllegalArgumentException("Slice count mismatch");
    }
    for (int z = zMin; z <= zMax; z++) {
      int[] r = runs[z];
      for (int i = 0; i < r.length; i += 2) {
        bits.flipRange(z, r[i], r[i] + r[i + 1]);
      }
    }
  }

  /** The diff flipping every set voxel of {@code bits}. */
  public static MaskDiff ofSetVoxels(VoxelBits bits) {
    Builder b = new Builder(bits.sizeZ());
    for (int z = 0; z < bits.sizeZ(); z++) {
      addRunsOfSlice(b, z, bits.sliceWords(z), bits.sliceBits());
    }
    return b.build();
  }

  /** Adds the runs of set bits of a slice given as words. */
  public static void addRunsOfSlice(Builder b, int z, long[] words, int sliceBits) {
    int start = -1;
    for (int w = 0; w < words.length; w++) {
      long bits = words[w];
      int base = w << 6;
      if (bits == 0) {
        if (start >= 0) {
          b.addRun(z, start, base - start);
          start = -1;
        }
        continue;
      }
      if (bits == -1L) {
        if (start < 0) {
          start = base;
        }
        continue;
      }
      for (int k = 0; k < 64; k++) {
        boolean set = (bits & (1L << k)) != 0;
        if (set && start < 0) {
          start = base + k;
        } else if (!set && start >= 0) {
          b.addRun(z, start, base + k - start);
          start = -1;
        }
      }
    }
    if (start >= 0) {
      b.addRun(z, start, Math.min(sliceBits, words.length << 6) - start);
    }
  }
}
