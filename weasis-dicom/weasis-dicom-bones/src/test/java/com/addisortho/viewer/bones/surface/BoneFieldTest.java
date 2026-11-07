/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.mesh.MeshChecks;
import com.addisortho.viewer.bones.mesh.ScalarGrid;
import com.addisortho.viewer.bones.mesh.TriangleMesh;
import org.junit.jupiter.api.Test;

class BoneFieldTest {

  private static final double BRIGHT = 1000;

  /** The bone fills x 0 to 10, a neighbouring segment x 10 to 20; y and z 0 to 10 for both. */
  private static int twoBlocks(double x, double y, double z) {
    if (y < 0 || y >= 10 || z < 0 || z >= 10) {
      return LabelSampler.NONE;
    }
    if (x >= 0 && x < 10) {
      return LabelSampler.BONE;
    }
    return x >= 10 && x < 20 ? LabelSampler.OTHER : LabelSampler.NONE;
  }

  /** 1 mm samples on whole millimetres, no smoothing: the field shows the mask exactly. */
  private static BoneField field() {
    SurfaceOptions options = new SurfaceOptions(200, true, 0, 1.0, 2, 0, 0, 1_000_000);
    return BoneField.build(
        BoneFieldTest::twoBlocks,
        new Box(0, 0, 0, 10, 10, 10),
        (_, _, _) -> BRIGHT,
        options,
        Progress.NONE);
  }

  private static float valueAt(BoneField field, double x, double y, double z) {
    ScalarGrid g = field.grid();
    int i = (int) Math.round((x - g.originX()) / g.spacingX());
    int j = (int) Math.round((y - g.originY()) / g.spacingY());
    int k = (int) Math.round((z - g.originZ()) / g.spacingZ());
    assertEquals(x, g.x(i), 1e-9, "not on a sample");
    assertEquals(y, g.y(j), 1e-9, "not on a sample");
    assertEquals(z, g.z(k), 1e-9, "not on a sample");
    return g.get(i, j, k);
  }

  @Test
  void ballOfRadiusTwoHasThirtyTwoOffsets() {
    assertEquals(32, BoneField.ball(2).length);
    assertEquals(6, BoneField.ball(1).length);
  }

  @Test
  void grownLabelStopsAtTheNeighbouringSegment() {
    BoneField field = field();
    float outside = field.outsideValue();
    assertEquals(-1000, outside);

    assertEquals(BRIGHT, valueAt(field, 5, 5, 5));
    assertEquals(BRIGHT, valueAt(field, 9, 5, 5));
    // The first sample of the neighbour is already closed to the bone.
    assertEquals(outside, valueAt(field, 10, 5, 5));
    assertEquals(outside, valueAt(field, 11, 5, 5));
  }

  @Test
  void grownLabelReachesTwoSamplesElsewhere() {
    BoneField field = field();
    float outside = field.outsideValue();

    // Bone samples span 0 to 9 on every axis.
    assertEquals(BRIGHT, valueAt(field, 5, 11, 5));
    assertEquals(outside, valueAt(field, 5, 12, 5));
    assertEquals(BRIGHT, valueAt(field, -2, 5, 5));
    assertEquals(outside, valueAt(field, -3, 5, 5));
    assertEquals(BRIGHT, valueAt(field, -1, -1, -1));
    assertEquals(outside, valueAt(field, -2, -2, 5));
    // Above the neighbour but not in it: grown, while within two samples of the bone.
    assertEquals(BRIGHT, valueAt(field, 10, 10, 5));
    assertEquals(outside, valueAt(field, 10, 11, 5));
  }

  @Test
  void surfaceStaysOutOfTheNeighbouringSegment() {
    SurfaceOptions options = SurfaceOptions.defaults();

    TriangleMesh mesh =
        BoneSurfaceBuilder.build(
                BoneFieldTest::twoBlocks,
                new Box(0, 0, 0, 10, 10, 10),
                (_, _, _) -> BRIGHT,
                options,
                Progress.NONE)
            .mesh();

    MeshChecks.assertClosedManifold(mesh);
    double contactFace = Double.NEGATIVE_INFINITY;
    for (int v = 0; v < mesh.vertexCount(); v++) {
      double x = mesh.coordinate(v, 0);
      double y = mesh.coordinate(v, 1);
      double z = mesh.coordinate(v, 2);
      // Not inside the neighbour, beyond the 0.6 mm sampling of its edges.
      assertTrue(
          !(x > 10.6 && y > 0.6 && y < 9.4 && z > 0.6 && z < 9.4),
          "vertex inside the neighbour at " + x + ", " + y + ", " + z);
      if (y > 1 && y < 9 && z > 1 && z < 9) {
        contactFace = Math.max(contactFace, x);
      }
    }
    // Where the two segments touch, the surface stops before the neighbour's first sample.
    assertTrue(contactFace < 10, "contact face at x = " + contactFace);
    double[] bounds = mesh.bounds();
    // Elsewhere the bright CT spreads to the edge of the grown label, about 1.2 mm out.
    assertTrue(bounds[0] < -0.5, "min x " + bounds[0]);
    assertTrue(bounds[4] > 10.5, "max y " + bounds[4]);
  }

  @Test
  void withoutCtTheFieldIsTheOccupancy() {
    SurfaceOptions options = new SurfaceOptions(200, true, 0, 1.0, 2, 0, 0, 1_000_000);

    BoneField field =
        BoneField.build(
            BoneFieldTest::twoBlocks, new Box(0, 0, 0, 10, 10, 10), null, options, Progress.NONE);

    assertTrue(!field.fromCt());
    assertEquals(0.5, field.iso());
    assertEquals(1, valueAt(field, 5, 5, 5));
    assertEquals(0, valueAt(field, 5, 11, 5));
    assertEquals(0, valueAt(field, 12, 5, 5));
  }
}
