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
 * Selected voxels of a grid kept as a bit volume over the smallest block holding them. Never
 * changed once built, so the bones of an undo step can share it.
 *
 * @param box the block, in grid coordinates
 * @param bits the voxels of the block; not to be changed
 */
public record BoxMask(VoxelBox box, BitVolume bits) implements VoxelMask {

  @Override
  public boolean contains(int x, int y, int z) {
    return box.contains(x, y, z) && bits.get(x - box.x0(), y - box.y0(), z - box.z0());
  }

  @Override
  public boolean hasVoxelsInSlice(int z) {
    return z >= box.z0() && z <= box.z1() && bits.countSlice(z - box.z0()) > 0;
  }

  @Override
  public VoxelBox bounds() {
    return box;
  }

  public long count() {
    return bits.count();
  }

  /** The mask of a grown region. */
  public static BoxMask of(RegionGrow.Result result) {
    return new BoxMask(result.box(), result.mask());
  }

  /**
   * Bits {@code local} of the block {@code block} trimmed to the voxels they hold, or {@code null}
   * when they hold none.
   */
  public static BoxMask trimmed(VoxelBox block, BitVolume local) {
    VoxelBox tight = local.bounds();
    if (tight == null) {
      return null;
    }
    BitVolume bits =
        tight.sizeX() == local.sizeX()
                && tight.sizeY() == local.sizeY()
                && tight.sizeZ() == local.sizeZ()
            ? local
            : local.crop(tight);
    return new BoxMask(
        new VoxelBox(
            block.x0() + tight.x0(),
            block.x0() + tight.x1(),
            block.y0() + tight.y0(),
            block.y0() + tight.y1(),
            block.z0() + tight.z0(),
            block.z0() + tight.z1()),
        bits);
  }

  /**
   * This mask without the voxels of {@code others}: the same instance when none of them overlaps
   * it, {@code null} when nothing is left.
   */
  public BoxMask minus(List<? extends VoxelMask> others) {
    BitVolume left = null;
    for (VoxelMask other : others) {
      VoxelBox common = box.intersect(other.bounds());
      if (common == null) {
        continue;
      }
      for (int z = common.z0(); z <= common.z1(); z++) {
        if (!other.hasVoxelsInSlice(z)) {
          continue;
        }
        for (int y = common.y0(); y <= common.y1(); y++) {
          for (int x = common.x0(); x <= common.x1(); x++) {
            if (other.contains(x, y, z) && contains(x, y, z)) {
              if (left == null) {
                left = bits.copy();
              }
              left.clear(x - box.x0(), y - box.y0(), z - box.z0());
            }
          }
        }
      }
    }
    return left == null ? this : trimmed(box, left);
  }

  /**
   * This mask without the voxels {@code removed} marks in this mask's block: {@code null} when
   * nothing is left.
   */
  public BoxMask minusLocal(BitVolume removed) {
    BitVolume left = bits.copy();
    left.andNot(removed);
    return trimmed(box, left);
  }
}
