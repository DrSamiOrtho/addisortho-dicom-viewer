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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaubinSmoothingTest {

  private static final double[] CENTRE = {0.3, -0.4, 0.2};
  private static final double RADIUS = 12;

  /** Spread of the vertex distances to the centre, in mm. */
  private static double radialSpread(TriangleMesh mesh) {
    double sum = 0;
    double sum2 = 0;
    for (int v = 0; v < mesh.vertexCount(); v++) {
      double dx = mesh.coordinate(v, 0) - CENTRE[0];
      double dy = mesh.coordinate(v, 1) - CENTRE[1];
      double dz = mesh.coordinate(v, 2) - CENTRE[2];
      double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
      sum += r;
      sum2 += r * r;
    }
    double mean = sum / mesh.vertexCount();
    return Math.sqrt(sum2 / mesh.vertexCount() - mean * mean);
  }

  @Test
  void smoothsWithoutShrinking() {
    // A coarse grid gives the staircase that smoothing must remove.
    ScalarGrid grid =
        MeshChecks.sphereField(
            32, new double[] {-16, -16, -16}, new double[] {1, 1, 1}, CENTRE, RADIUS);
    // Quantising the field to whole samples mimics a binary label.
    for (int k = 0; k < 32; k++) {
      for (int j = 0; j < 32; j++) {
        for (int i = 0; i < 32; i++) {
          grid.set(i, j, k, grid.get(i, j, k) >= 0 ? 1 : 0);
        }
      }
    }
    TriangleMesh blocky = MarchingCubes.extract(grid, 0.5);

    TriangleMesh smooth = TaubinSmoothing.smooth(blocky, 15);

    MeshChecks.assertClosedManifold(smooth);
    assertEquals(blocky.triangleCount(), smooth.triangleCount());
    assertTrue(radialSpread(smooth) < 0.6 * radialSpread(blocky), "staircase not reduced");
    assertEquals(blocky.signedVolume(), smooth.signedVolume(), 0.02 * blocky.signedVolume());
  }

  @Test
  void zeroIterationsReturnsTheMesh() {
    TriangleMesh mesh =
        MarchingCubes.extract(
            MeshChecks.sphereField(
                12, new double[] {-6, -6, -6}, new double[] {1, 1, 1}, new double[3], 4),
            0);
    assertSame(mesh, TaubinSmoothing.smooth(mesh, 0));
  }
}
