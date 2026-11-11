/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cuts of neighbouring seams stay in order down the leg (review of 11 Nov 2026). The compositor
 * takes piece i between cut i-1 and cut i; with the cuts out of order the piece between them is
 * left out and its neighbours, lined up only through it, are averaged in its place.
 */
class StitchCutOrderTest {

  private static List<Piece> pieces;
  private static List<Seam> seams;

  @BeforeAll
  static void build() throws IOException {
    pieces = StitchFixture.allOverlapping().pieces();
    seams = StitchFixture.seams(pieces, StitchFixture.lineUp(2));
  }

  private static Stitch stitch(double... cuts) {
    return new Stitch(pieces, seams, StitchFixture.CHOICES, cuts);
  }

  @Test
  void theFixtureLetsTheCutsCross() {
    for (Seam s : seams) {
      assertEquals(Seam.Status.CHECK, s.status(), s.reasons().toString());
    }
    Stitch automatic = stitch(Double.NaN, Double.NaN);
    assertTrue(automatic.seamY(0) < automatic.seamY(1), "automatic cuts come out in order");
    // seam 1's cut may lie as low as 290 mm in piece 1, seam 2's as high as about 220 mm on the
    // frame: without the rule they cross
    assertTrue(280 > automatic.seamY(1) - 60, "the overlaps overlap");
  }

  @Test
  void aCutDraggedPastAnAutomaticNeighbourStopsShortOfIt() {
    Stitch automatic = stitch(Double.NaN, Double.NaN);
    double next = automatic.seamY(1);

    Stitch dragged = stitch(289, Double.NaN); // seam 1's line dragged to the foot of piece 1
    assertEquals(automatic.cut(1), dragged.cut(1), "the automatic cut stays");
    assertEquals(next, dragged.seamY(1), 1e-9);
    assertTrue(
        dragged.seamY(0) <= next - Stitch.FEATHER_MM + 1e-6,
        "seam 1 at %.1f, seam 2 at %.1f".formatted(dragged.seamY(0), next));
    if (next - Stitch.FEATHER_MM < 289) {
      assertTrue(dragged.cut(0).note().contains("held clear of seam 2"), dragged.cut(0).note());
    }

    Stitch up = stitch(Double.NaN, 1); // seam 2's line dragged to the top of its overlap
    assertEquals(automatic.cut(0), up.cut(0));
    assertTrue(
        up.seamY(1) >= up.seamY(0) + Stitch.FEATHER_MM - 1e-6,
        "seam 1 at %.1f, seam 2 at %.1f".formatted(up.seamY(0), up.seamY(1)));
  }

  @Test
  void twoHandCutsKeepTheirOrderAndTheUpperOneStays() {
    Stitch both = stitch(285, 95); // seam 2's at about 215 mm on the frame, above seam 1's
    assertEquals(285, both.cut(0).yMm(), 1e-9, "the upper seam's cut stays where it was put");
    assertTrue(
        both.seamY(1) >= both.seamY(0) + Stitch.FEATHER_MM - 1e-6,
        "seam 1 at %.1f, seam 2 at %.1f".formatted(both.seamY(0), both.seamY(1)));
    assertTrue(both.cut(1).note().contains("held clear of seam 1"), both.cut(1).note());
  }

  @Test
  void cutsInOrderAreLeftAlone() {
    Stitch s = stitch(200, 200);
    assertEquals(200, s.cut(0).yMm(), 1e-9);
    assertEquals(200, s.cut(1).yMm(), 1e-9);
    assertEquals("cut placed by hand", s.cut(0).note());
    assertEquals("cut placed by hand", s.cut(1).note());
  }

  /** With the cuts held in order no part of the middle piece's band is left to its neighbours. */
  @Test
  void theMiddlePieceIsDrawnBetweenTheCuts() {
    Stitch s = stitch(289, 95);
    Gray g = new Compositor(s).preview(MatchImage.COARSE_MM, false);
    double[] b = s.bounds();
    int row = (int) (((s.seamY(0) + s.seamY(1)) / 2 - b[1]) / g.mm());
    int finite = 0;
    for (int c = 0; c < g.width(); c++) {
      if (Float.isFinite(g.data()[row * g.width() + c])) {
        finite++;
      }
    }
    assertTrue(finite > g.width() / 2, "the band between the cuts is drawn");
  }
}
