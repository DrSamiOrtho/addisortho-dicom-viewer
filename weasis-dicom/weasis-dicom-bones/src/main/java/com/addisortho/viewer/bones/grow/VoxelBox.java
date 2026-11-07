/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/** An axis-aligned block of voxels of the working grid, bounds included. */
public record VoxelBox(int x0, int x1, int y0, int y1, int z0, int z1) {

  public VoxelBox {
    if (x0 > x1 || y0 > y1 || z0 > z1) {
      throw new IllegalArgumentException("Empty voxel box");
    }
  }

  /** The whole grid. */
  public static VoxelBox of(int sizeX, int sizeY, int sizeZ) {
    return new VoxelBox(0, sizeX - 1, 0, sizeY - 1, 0, sizeZ - 1);
  }

  public int sizeX() {
    return x1 - x0 + 1;
  }

  public int sizeY() {
    return y1 - y0 + 1;
  }

  public int sizeZ() {
    return z1 - z0 + 1;
  }

  public long voxels() {
    return (long) sizeX() * sizeY() * sizeZ();
  }

  public boolean contains(int x, int y, int z) {
    return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
  }

  /** The part of this box inside {@code other}, or {@code null} when they do not overlap. */
  public VoxelBox intersect(VoxelBox other) {
    int ax = Math.max(x0, other.x0);
    int bx = Math.min(x1, other.x1);
    int ay = Math.max(y0, other.y0);
    int by = Math.min(y1, other.y1);
    int az = Math.max(z0, other.z0);
    int bz = Math.min(z1, other.z1);
    return ax > bx || ay > by || az > bz ? null : new VoxelBox(ax, bx, ay, by, az, bz);
  }
}
