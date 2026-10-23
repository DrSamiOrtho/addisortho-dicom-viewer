/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import java.util.List;

/**
 * Hand edits of a bone's mask: a brush that paints bone-dense voxels into it or rubs voxels out of
 * it around a clicked voxel, and a smoothing of its surface.
 */
public final class MaskEdit {

  private MaskEdit() {}

  /**
   * The voxels of the brush around {@code centre}: an ellipsoid of {@code radius} voxels per axis.
   */
  public static BoxMask brush(int[] centre, double[] radius, VoxelBox grid) {
    int rx = (int) Math.ceil(radius[0]);
    int ry = (int) Math.ceil(radius[1]);
    int rz = (int) Math.ceil(radius[2]);
    VoxelBox block =
        new VoxelBox(
                centre[0] - rx, centre[0] + rx,
                centre[1] - ry, centre[1] + ry,
                centre[2] - rz, centre[2] + rz)
            .intersect(grid);
    if (block == null) {
      return null;
    }
    BitVolume bits = new BitVolume(block.sizeX(), block.sizeY(), block.sizeZ());
    for (int z = block.z0(); z <= block.z1(); z++) {
      double dz = (z - centre[2]) / radius[2];
      for (int y = block.y0(); y <= block.y1(); y++) {
        double dy = (y - centre[1]) / radius[1];
        for (int x = block.x0(); x <= block.x1(); x++) {
          double dx = (x - centre[0]) / radius[0];
          if (dx * dx + dy * dy + dz * dz <= 1.0) {
            bits.set(x - block.x0(), y - block.y0(), z - block.z0());
          }
        }
      }
    }
    return BoxMask.trimmed(block, bits);
  }

  /**
   * The voxels of {@code brush} that look like bone on the CT: at or above {@code lowerHu}, and not
   * above {@code upperHu}. {@code null} when there is none.
   */
  public static BoxMask dense(BoxMask brush, HuSource hu, double lowerHu, double upperHu) {
    VoxelBox b = brush.box();
    BitVolume bits = new BitVolume(b.sizeX(), b.sizeY(), b.sizeZ());
    for (int z = b.z0(); z <= b.z1(); z++) {
      for (int y = b.y0(); y <= b.y1(); y++) {
        for (int x = b.x0(); x <= b.x1(); x++) {
          if (brush.contains(x, y, z)) {
            double v = hu.huAt(x, y, z);
            if (v >= lowerHu && v <= upperHu) {
              bits.set(x - b.x0(), y - b.y0(), z - b.z0());
            }
          }
        }
      }
    }
    return BoxMask.trimmed(b, bits);
  }

  /** {@code mask} with the voxels of {@code added}; the same instance when it has them all. */
  public static BoxMask plus(BoxMask mask, BoxMask added) {
    if (added == null || added.minus(List.of(mask)) == null) {
      return mask;
    }
    return BoxMask.union(List.of(mask, added));
  }

  /** A voxel is bone after a smoothing round when at least this many of its 27 neighbours are. */
  private static final int MAJORITY = 14;

  /**
   * {@code mask} with its surface smoothed by {@code rounds} majority rounds: a voxel is kept, or
   * added, when most of the 3 x 3 x 3 voxels around it are bone. Each round fills pits and takes
   * off spikes of a voxel and rounds off edges by a voxel; a plate two voxels thick stays. {@code
   * null} when nothing is left.
   *
   * @throws java.util.concurrent.CancellationException when {@code caller} is interrupted
   */
  public static BoxMask smooth(BoxMask mask, int rounds, Thread caller) {
    if (rounds <= 0) {
      return mask;
    }
    BitVolume bits = mask.bits().padded(rounds);
    for (int r = 0; r < rounds; r++) {
      bits = majority(bits, caller);
    }
    VoxelBox b = mask.box();
    VoxelBox block =
        new VoxelBox(
            b.x0() - rounds, b.x1() + rounds,
            b.y0() - rounds, b.y1() + rounds,
            b.z0() - rounds, b.z1() + rounds);
    return BoxMask.trimmed(block, bits);
  }

  /** One majority round, with the voxels beyond the volume counted as clear. */
  static BitVolume majority(BitVolume v, Thread caller) {
    int sx = v.sizeX();
    int sy = v.sizeY();
    int sz = v.sizeZ();
    BitVolume out = v.emptyCopy();
    int[] alongZ = new int[sx * sy]; // bone voxels at z - 1, z and z + 1
    int[] alongYz = new int[sx * sy]; // the same summed over y - 1, y and y + 1
    for (int z = 0; z < sz; z++) {
      RegionGrow.checkCancelled(caller);
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          int n = v.get(x, y, z) ? 1 : 0;
          if (z > 0 && v.get(x, y, z - 1)) {
            n++;
          }
          if (z < sz - 1 && v.get(x, y, z + 1)) {
            n++;
          }
          alongZ[y * sx + x] = n;
        }
      }
      for (int y = 0; y < sy; y++) {
        for (int x = 0; x < sx; x++) {
          int n = alongZ[y * sx + x];
          if (y > 0) {
            n += alongZ[(y - 1) * sx + x];
          }
          if (y < sy - 1) {
            n += alongZ[(y + 1) * sx + x];
          }
          alongYz[y * sx + x] = n;
        }
      }
      for (int y = 0; y < sy; y++) {
        int row = y * sx;
        for (int x = 0; x < sx; x++) {
          int n = alongYz[row + x];
          if (x > 0) {
            n += alongYz[row + x - 1];
          }
          if (x < sx - 1) {
            n += alongYz[row + x + 1];
          }
          if (n >= MAJORITY) {
            out.set(x, y, z);
          }
        }
      }
    }
    return out;
  }
}
