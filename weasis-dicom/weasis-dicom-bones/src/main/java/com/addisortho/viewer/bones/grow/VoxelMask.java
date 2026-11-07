/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/** Selected voxels of a grid. */
public interface VoxelMask {

  /** Whether voxel {@code (x, y, z)} of the grid is selected. */
  boolean contains(int x, int y, int z);

  /** Whether slice {@code z} of the grid holds a selected voxel. */
  boolean hasVoxelsInSlice(int z);

  /** A box of the grid holding every selected voxel. */
  VoxelBox bounds();
}
