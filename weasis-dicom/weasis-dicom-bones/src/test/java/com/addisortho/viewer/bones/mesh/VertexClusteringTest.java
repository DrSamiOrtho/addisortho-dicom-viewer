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

class VertexClusteringTest {

  private static final double RADIUS = 15;

  private static TriangleMesh sphere() {
    return MarchingCubes.extract(
        MeshChecks.sphereField(
            40,
            new double[] {-20, -20, -20},
            new double[] {1, 1, 1},
            new double[] {0.2, 0.1, -0.3},
            RADIUS),
        0);
  }

  @Test
  void reducesToTheTarget() {
    TriangleMesh mesh = sphere();
    int target = mesh.triangleCount() / 5;

    TriangleMesh reduced = VertexClustering.decimate(mesh, target);

    assertTrue(reduced.triangleCount() <= target, reduced.triangleCount() + " > " + target);
    assertTrue(reduced.triangleCount() > target / 4, "reduced far more than asked");
    assertEquals(mesh.signedVolume(), reduced.signedVolume(), 0.05 * mesh.signedVolume());
    double[] c = reduced.centroid();
    assertEquals(0.2, c[0], 0.3);
    assertEquals(0.1, c[1], 0.3);
    assertEquals(-0.3, c[2], 0.3);
  }

  @Test
  void leavesSmallMeshesAlone() {
    TriangleMesh mesh = sphere();
    assertSame(mesh, VertexClustering.decimate(mesh, mesh.triangleCount()));
    assertSame(mesh, VertexClustering.decimate(mesh, 0));
  }
}
