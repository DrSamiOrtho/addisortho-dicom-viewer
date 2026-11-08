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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class MarchingCubesTest {

  @Test
  void tableUsesExactlyTheCrossedEdges() {
    assertEquals(256, CubeTables.TRIANGLES.length);
    for (int cubeCase = 0; cubeCase < 256; cubeCase++) {
      Set<Integer> crossed = new TreeSet<>();
      for (int e = 0; e < 12; e++) {
        int[] corners = CubeTables.EDGES[e];
        boolean below0 = (cubeCase & (1 << corners[0])) != 0;
        boolean below1 = (cubeCase & (1 << corners[1])) != 0;
        if (below0 != below1) {
          crossed.add(e);
        }
      }
      int[] row = CubeTables.TRIANGLES[cubeCase];
      assertEquals(0, row.length % 3, "case " + cubeCase);
      Set<Integer> used = new TreeSet<>();
      for (int e : row) {
        used.add(e);
      }
      assertEquals(crossed, used, "case " + cubeCase);
    }
  }

  @Test
  void sphereIsClosedOutwardAndAccurate() {
    double[] centre = {-102.3, 47.9, -611.2};
    double radius = 10;
    ScalarGrid grid =
        MeshChecks.sphereField(
            28, new double[] {-116.0, 34.0, -625.0}, new double[] {1, 1, 1}, centre, radius);

    TriangleMesh mesh = MarchingCubes.extract(grid, 0);

    MeshChecks.assertClosedManifold(mesh);
    assertEquals(2, MeshChecks.eulerCharacteristic(mesh));
    double volume = mesh.signedVolume();
    assertTrue(volume > 0, "normals must point outward");
    assertEquals(MeshChecks.sphereVolume(radius), volume, 0.02 * MeshChecks.sphereVolume(radius));
    double[] c = mesh.centroid();
    for (int axis = 0; axis < 3; axis++) {
      assertEquals(centre[axis], c[axis], 0.05);
    }
  }

  @Test
  void anisotropicGridKeepsTheGeometry() {
    double[] centre = {3.1, -2.7, 40.4};
    double radius = 9;
    ScalarGrid grid =
        new ScalarGrid(36, 36, 14, new double[] {-10, -16, 25}, new double[] {0.7, 0.7, 2.5});
    for (int k = 0; k < grid.sizeZ(); k++) {
      for (int j = 0; j < grid.sizeY(); j++) {
        for (int i = 0; i < grid.sizeX(); i++) {
          double dx = grid.x(i) - centre[0];
          double dy = grid.y(j) - centre[1];
          double dz = grid.z(k) - centre[2];
          grid.set(i, j, k, (float) (radius - Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
      }
    }

    TriangleMesh mesh = MarchingCubes.extract(grid, 0);

    MeshChecks.assertClosedManifold(mesh);
    assertEquals(
        MeshChecks.sphereVolume(radius),
        mesh.signedVolume(),
        0.03 * MeshChecks.sphereVolume(radius));
  }

  @Test
  void randomFieldGivesClosedSurfaces() {
    // Every ambiguous face configuration occurs in noise; neighbouring cells must agree on all.
    Random random = new Random(20261014);
    for (int run = 0; run < 5; run++) {
      ScalarGrid grid = new ScalarGrid(14, 13, 12, new double[3], new double[] {1, 1, 1});
      for (int k = 0; k < grid.sizeZ(); k++) {
        for (int j = 0; j < grid.sizeY(); j++) {
          for (int i = 0; i < grid.sizeX(); i++) {
            grid.set(i, j, k, random.nextFloat());
          }
        }
      }
      TriangleMesh mesh = MarchingCubes.extract(grid, 0.5);
      MeshChecks.assertClosedManifold(mesh);
    }
  }

  @Test
  void fieldTouchingTheGridEdgeIsClosedThere() {
    ScalarGrid grid = new ScalarGrid(4, 4, 4, new double[3], new double[] {1, 1, 1});
    for (int k = 0; k < 4; k++) {
      for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
          grid.set(i, j, k, 1);
        }
      }
    }

    TriangleMesh mesh = MarchingCubes.extract(grid, 0.5);

    MeshChecks.assertClosedManifold(mesh);
    // A 3 mm cube between the outer samples, capped at the samples on the grid edge.
    assertEquals(27, mesh.signedVolume(), 0.5);
  }

  @Test
  void nothingAboveTheIsoValueGivesNoTriangles() {
    ScalarGrid grid = new ScalarGrid(5, 5, 5, new double[3], new double[] {1, 1, 1});
    assertTrue(MarchingCubes.extract(grid, 0.5).isEmpty());
  }
}
