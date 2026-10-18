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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

/** Assertions on mesh topology and test shapes shared by the tests. */
public final class MeshChecks {

  private MeshChecks() {}

  /**
   * Every edge is used by exactly two triangles, once in each direction: the surface is closed,
   * edge-manifold and consistently oriented.
   */
  public static void assertClosedManifold(TriangleMesh mesh) {
    assertFalse(mesh.isEmpty(), "empty mesh");
    Set<Long> directed = new HashSet<>();
    for (int t = 0; t < mesh.triangleCount(); t++) {
      for (int c = 0; c < 3; c++) {
        int a = mesh.corner(t, c);
        int b = mesh.corner(t, (c + 1) % 3);
        assertTrue(a != b, "degenerate triangle " + t);
        assertTrue(
            directed.add(key(a, b)), "edge used twice in the same direction: " + a + "-" + b);
      }
    }
    for (long edge : directed) {
      int a = (int) (edge >>> 32);
      int b = (int) edge;
      assertTrue(directed.contains(key(b, a)), "open edge " + a + "-" + b);
    }
    assertEquals(3 * mesh.triangleCount(), directed.size());
  }

  /** Number of vertices minus edges plus faces: 2 per sphere-like piece. */
  public static int eulerCharacteristic(TriangleMesh mesh) {
    Set<Long> edges = new HashSet<>();
    Set<Integer> used = new HashSet<>();
    for (int t = 0; t < mesh.triangleCount(); t++) {
      for (int c = 0; c < 3; c++) {
        int a = mesh.corner(t, c);
        int b = mesh.corner(t, (c + 1) % 3);
        edges.add(key(Math.min(a, b), Math.max(a, b)));
        used.add(a);
      }
    }
    return used.size() - edges.size() + mesh.triangleCount();
  }

  /** A grid whose value is {@code radius - distance} to {@code centre}: positive inside. */
  public static ScalarGrid sphereField(
      int size, double[] origin, double[] spacing, double[] centre, double radius) {
    ScalarGrid grid = new ScalarGrid(size, size, size, origin, spacing);
    for (int k = 0; k < size; k++) {
      for (int j = 0; j < size; j++) {
        for (int i = 0; i < size; i++) {
          double dx = grid.x(i) - centre[0];
          double dy = grid.y(j) - centre[1];
          double dz = grid.z(k) - centre[2];
          grid.set(i, j, k, (float) (radius - Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
      }
    }
    return grid;
  }

  public static double sphereVolume(double radius) {
    return 4.0 / 3.0 * Math.PI * radius * radius * radius;
  }

  private static long key(int a, int b) {
    return ((long) a << 32) | (b & 0xFFFFFFFFL);
  }
}
