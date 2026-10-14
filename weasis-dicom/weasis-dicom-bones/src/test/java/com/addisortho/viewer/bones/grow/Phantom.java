/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import java.util.concurrent.atomic.AtomicLong;

/** A synthetic CT on a 1 mm grid, built voxel by voxel, which counts the voxels it is asked for. */
final class Phantom implements HuSource {

  /** CT value of a voxel of the phantom. */
  @FunctionalInterface
  interface Shape {
    double hu(int x, int y, int z);
  }

  final int sizeX;
  final int sizeY;
  final int sizeZ;
  private final float[] hu;
  private final AtomicLong reads = new AtomicLong();

  Phantom(int sizeX, int sizeY, int sizeZ, Shape shape) {
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.hu = new float[sizeX * sizeY * sizeZ];
    for (int z = 0; z < sizeZ; z++) {
      for (int y = 0; y < sizeY; y++) {
        for (int x = 0; x < sizeX; x++) {
          hu[(z * sizeY + y) * sizeX + x] = (float) shape.hu(x, y, z);
        }
      }
    }
  }

  @Override
  public double huAt(int x, int y, int z) {
    reads.incrementAndGet();
    if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) {
      return Double.NaN;
    }
    return hu[(z * sizeY + y) * sizeX + x];
  }

  long reads() {
    return reads.get();
  }

  static boolean inDisc(int x, int y, double cx, double cy, double radius) {
    double dx = x - cx;
    double dy = y - cy;
    return dx * dx + dy * dy <= radius * radius;
  }

  /**
   * Two bones along z, as a femur over a tibia: discs of radius 8 around (20, 20), 700 HU, z 2 to
   * 17 and z 20 to 37, with a 2 mm joint space of 0 HU at z 18 and 19 crossed by a one-voxel bridge
   * of 300 HU at (20, 20).
   */
  static Phantom twoBones() {
    return new Phantom(
        40,
        40,
        40,
        (x, y, z) -> {
          if (!inDisc(x, y, 20, 20, 8)) {
            return 0;
          }
          if ((z >= 2 && z <= 17) || (z >= 20 && z <= 37)) {
            return 700;
          }
          return (z == 18 || z == 19) && x == 20 && y == 20 ? 300 : 0;
        });
  }

  static boolean upperBone(int x, int y, int z) {
    return inDisc(x, y, 20, 20, 8) && z >= 2 && z <= 17;
  }

  static boolean lowerBone(int x, int y, int z) {
    return inDisc(x, y, 20, 20, 8) && z >= 20 && z <= 37;
  }
}
