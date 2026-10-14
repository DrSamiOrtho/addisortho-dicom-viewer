/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer3d.vr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Point2D;
import java.util.List;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

class LassoCutTest {

  private static final int N = 24;
  private static final int W = 200;
  private static final int H = 160;

  private static final VolumeSpace SPACE =
      VolumeSpace.ofVolume(new Vector3i(N, N, N), new Vector3d(0.8, 0.8, 1.5));

  /** A perspective camera looking at the volume from the front, slightly turned. */
  private static Matrix4d viewProjection() {
    Quaterniond rotation = new Quaterniond().rotationXYZ(Math.toRadians(-80), 0, 0.3);
    Matrix4d view = new Matrix4d().setTranslation(new Vector3d(0.1, 0, -3.2)).rotate(rotation);
    Matrix4d proj = new Matrix4d().setPerspective(Math.toRadians(45), W / (double) H, 0.1, 100);
    return proj.mul(view);
  }

  private static Point2D project(Matrix4d vp, int x, int y, int z) {
    Vector3d t = SPACE.texCoordOfGrid(x, y, z, new Vector3d());
    Vector3d w = SPACE.worldOfTexCoord(t, new Vector3d());
    Vector4d c = vp.transform(new Vector4d(w, 1));
    return new Point2D.Double((c.x / c.w + 1) / 2 * W, (1 - c.y / c.w) / 2 * H);
  }

  private static boolean inTriangle(Point2D p, List<Point2D> tri) {
    double d1 = sign(p, tri.get(0), tri.get(1));
    double d2 = sign(p, tri.get(1), tri.get(2));
    double d3 = sign(p, tri.get(2), tri.get(0));
    boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
    boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
    return !(neg && pos);
  }

  private static double sign(Point2D p, Point2D a, Point2D b) {
    return (p.getX() - b.getX()) * (a.getY() - b.getY())
        - (a.getX() - b.getX()) * (p.getY() - b.getY());
  }

  @Test
  void cutInsideRemovesTheVoxelsProjectingIntoTheArea() {
    Matrix4d vp = viewProjection();
    List<Point2D> triangle =
        List.of(
            new Point2D.Double(40, 30), new Point2D.Double(150, 50), new Point2D.Double(90, 140));
    VoxelBits kept = new VoxelBits(N, N, N);
    kept.fill(true);
    LassoCut.Result r = LassoCut.compute(SPACE, vp, W, H, triangle, LassoCut.Side.INSIDE, kept);
    r.diff().applyTo(kept);
    assertTrue(r.diff().voxels() > 0);
    assertTrue(r.rowsSkipped() > 0, "rows outside the area are settled without being visited");

    int disagreements = 0;
    for (int z = 0; z < N; z++) {
      for (int y = 0; y < N; y++) {
        for (int x = 0; x < N; x++) {
          Point2D p = project(vp, x, y, z);
          boolean inside = inTriangle(p, triangle);
          boolean removed = !kept.get(x, y, z);
          if (inside != removed) {
            // Only a voxel whose centre is within a pixel of the edge may differ: the area is
            // rasterised at pixel resolution.
            disagreements++;
            assertTrue(nearEdge(p, triangle), "voxel " + x + "," + y + "," + z + " at " + p);
          }
        }
      }
    }
    assertTrue(disagreements < N * N * N / 20, "too many edge cases: " + disagreements);
  }

  private static boolean nearEdge(Point2D p, List<Point2D> tri) {
    for (int i = 0; i < 3; i++) {
      Point2D a = tri.get(i);
      Point2D b = tri.get((i + 1) % 3);
      if (java.awt.geom.Line2D.ptSegDist(a.getX(), a.getY(), b.getX(), b.getY(), p.getX(), p.getY())
          <= 1.5) {
        return true;
      }
    }
    return false;
  }

  @Test
  void cutOutsideIsTheComplementOfCutInside() {
    Matrix4d vp = viewProjection();
    List<Point2D> area =
        List.of(
            new Point2D.Double(60, 40),
            new Point2D.Double(140, 40),
            new Point2D.Double(140, 120),
            new Point2D.Double(60, 120));
    VoxelBits keptIn = new VoxelBits(N, N, N);
    keptIn.fill(true);
    VoxelBits keptOut = new VoxelBits(N, N, N);
    keptOut.fill(true);
    MaskDiff inside = LassoCut.compute(SPACE, vp, W, H, area, LassoCut.Side.INSIDE, keptIn).diff();
    MaskDiff outside =
        LassoCut.compute(SPACE, vp, W, H, area, LassoCut.Side.OUTSIDE, keptOut).diff();
    assertEquals((long) N * N * N, inside.voxels() + outside.voxels());
    inside.applyTo(keptIn);
    outside.applyTo(keptOut);
    for (int z = 0; z < N; z++) {
      for (int y = 0; y < N; y++) {
        for (int x = 0; x < N; x++) {
          assertTrue(keptIn.get(x, y, z) != keptOut.get(x, y, z));
        }
      }
    }
  }

  @Test
  void onlyShownVoxelsAreRemovedSoTheDiffUndoesExactly() {
    Matrix4d vp = viewProjection();
    List<Point2D> area =
        List.of(
            new Point2D.Double(0, 0),
            new Point2D.Double(W, 0),
            new Point2D.Double(W, H),
            new Point2D.Double(0, H));
    VoxelBits kept = new VoxelBits(N, N, N);
    kept.fill(true);
    kept.flipRange(3, 0, N * N); // slice 3 already removed
    MaskDiff diff = LassoCut.compute(SPACE, vp, W, H, area, LassoCut.Side.INSIDE, kept).diff();
    assertEquals((long) N * N * (N - 1), diff.voxels());
    diff.applyTo(kept);
    assertEquals(0, kept.count());
    diff.applyTo(kept);
    assertEquals((long) N * N * (N - 1), kept.count());
    assertFalse(kept.get(1, 1, 3));
  }
}
