/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

/** An axis-aligned box in patient coordinates, mm (DICOM LPS). */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

  public Box {
    if (!(minX <= maxX && minY <= maxY && minZ <= maxZ)) {
      throw new IllegalArgumentException("Empty box");
    }
  }

  /** The smallest box holding the given points, as x, y, z triples. */
  public static Box around(double... xyz) {
    double[] b = {
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    for (int n = 0; n + 2 < xyz.length; n += 3) {
      for (int axis = 0; axis < 3; axis++) {
        b[axis] = Math.min(b[axis], xyz[n + axis]);
        b[axis + 3] = Math.max(b[axis + 3], xyz[n + axis]);
      }
    }
    return new Box(b[0], b[1], b[2], b[3], b[4], b[5]);
  }

  public Box expand(double margin) {
    return new Box(
        minX - margin, minY - margin, minZ - margin, maxX + margin, maxY + margin, maxZ + margin);
  }
}
