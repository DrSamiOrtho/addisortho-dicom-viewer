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

import java.util.List;
import org.junit.jupiter.api.Test;

class MeshPiecesTest {

  /** Two balls: radius 6 around (0, 0, 0) and radius 4 around (16, 0, 0). */
  private static TriangleMesh twoBalls() {
    ScalarGrid grid = new ScalarGrid(36, 18, 18, new double[] {-9, -9, -9}, new double[] {1, 1, 1});
    for (int k = 0; k < grid.sizeZ(); k++) {
      for (int j = 0; j < grid.sizeY(); j++) {
        for (int i = 0; i < grid.sizeX(); i++) {
          double y = grid.y(j);
          double z = grid.z(k);
          double big = 6 - Math.sqrt(grid.x(i) * grid.x(i) + y * y + z * z);
          double dx = grid.x(i) - 16;
          double small = 4 - Math.sqrt(dx * dx + y * y + z * z);
          grid.set(i, j, k, (float) Math.max(big, small));
        }
      }
    }
    return MarchingCubes.extract(grid, 0);
  }

  @Test
  void splitsLargestFirst() {
    TriangleMesh mesh = twoBalls();

    List<MeshPieces.Piece> pieces = MeshPieces.split(mesh);

    assertEquals(2, pieces.size());
    assertEquals(
        MeshChecks.sphereVolume(6), pieces.get(0).volume(), 0.03 * MeshChecks.sphereVolume(6));
    assertEquals(
        MeshChecks.sphereVolume(4), pieces.get(1).volume(), 0.05 * MeshChecks.sphereVolume(4));
    assertEquals(
        mesh.triangleCount(), pieces.get(0).triangles().length + pieces.get(1).triangles().length);
  }

  @Test
  void keepsTheLargestBall() {
    TriangleMesh largest = MeshPieces.keepLargest(twoBalls());

    MeshChecks.assertClosedManifold(largest);
    assertEquals(1, MeshPieces.split(largest).size());
    double[] bounds = largest.bounds();
    assertTrue(bounds[3] < 7, "the small ball at x = 16 must be gone");
  }

  @Test
  void cavityCountsAsAPiece() {
    // A shell: the inner surface faces inward and encloses a negative volume.
    ScalarGrid grid =
        MeshChecks.sphereField(
            26, new double[] {-13, -13, -13}, new double[] {1, 1, 1}, new double[3], 10);
    for (int k = 0; k < 26; k++) {
      for (int j = 0; j < 26; j++) {
        for (int i = 0; i < 26; i++) {
          float outer = grid.get(i, j, k);
          grid.set(i, j, k, Math.min(outer, 5 - outer));
        }
      }
    }
    TriangleMesh shell = MarchingCubes.extract(grid, 0);

    List<MeshPieces.Piece> pieces = MeshPieces.split(shell);

    assertEquals(2, pieces.size());
    assertEquals(
        MeshChecks.sphereVolume(10) - MeshChecks.sphereVolume(5),
        shell.signedVolume(),
        0.03 * MeshChecks.sphereVolume(10));
    assertTrue(pieces.get(0).volume() > pieces.get(1).volume());
  }
}
