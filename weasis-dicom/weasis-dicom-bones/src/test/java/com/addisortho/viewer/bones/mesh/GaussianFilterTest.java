/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GaussianFilterTest {

  @Test
  void kernelIsNormalised() {
    float[] kernel = GaussianFilter.kernel(1.3);
    double sum = 0;
    for (float w : kernel) {
      sum += w;
    }
    assertEquals(1, sum, 1e-5);
    assertEquals(9, kernel.length);
  }

  @Test
  void keepsTheTotalAndSpreadsAPeak() {
    ScalarGrid grid = new ScalarGrid(21, 21, 21, new double[3], new double[] {0.5, 0.5, 1});
    grid.set(10, 10, 10, 1000);

    GaussianFilter.smooth(grid, 1.0);

    double total = 0;
    for (int k = 0; k < 21; k++) {
      for (int j = 0; j < 21; j++) {
        for (int i = 0; i < 21; i++) {
          total += grid.get(i, j, k);
        }
      }
    }
    assertEquals(1000, total, 0.1);
    // Same sigma in mm: the profile is twice as wide in samples along x as along z.
    double alongX = grid.get(12, 10, 10) / grid.get(10, 10, 10);
    double alongZ = grid.get(10, 10, 11) / grid.get(10, 10, 10);
    assertEquals(Math.exp(-0.5), alongX, 0.02);
    assertEquals(Math.exp(-0.5), alongZ, 0.02);
  }

  @Test
  void constantFieldStaysConstant() {
    ScalarGrid grid = new ScalarGrid(5, 6, 7, new double[3], new double[] {1, 1, 1});
    for (int k = 0; k < 7; k++) {
      for (int j = 0; j < 6; j++) {
        for (int i = 0; i < 5; i++) {
          grid.set(i, j, k, 300);
        }
      }
    }

    GaussianFilter.smooth(grid, 2);

    assertEquals(300, grid.get(0, 0, 0), 1e-3);
    assertEquals(300, grid.get(4, 5, 6), 1e-3);
  }
}
