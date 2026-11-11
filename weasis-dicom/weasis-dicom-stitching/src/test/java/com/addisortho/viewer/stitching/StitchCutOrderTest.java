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

  /** Wherever the two lines are dragged, the cuts come out in order (Astra, 11 Nov 2026). */
  @Test
  void noPairOfHandPositionsLeavesTheCutsOutOfOrder() {
    for (double first : new double[] {1, 135, 200, 260, 289, 400}) {
      for (double second : new double[] {1, 91, 95, 150, 289, 400, Double.NaN}) {
        Stitch s = stitch(first, second);
        assertTrue(s.cutsInOrder(), "%.0f and %.0f".formatted(first, second));
        assertTrue(
            s.seamY(1) >= s.seamY(0) + Stitch.FEATHER_MM - 1e-6,
            "%.0f and %.0f: seam 1 at %.1f, seam 2 at %.1f"
                .formatted(first, second, s.seamY(0), s.seamY(1)));
      }
      assertTrue(stitch(Double.NaN, first).cutsInOrder());
    }
  }

  private static Seam at(Rigid transform) {
    return new Seam(
        transform,
        Seam.Method.AUTOMATIC,
        Seam.Status.CHECK,
        0.2,
        0.01,
        0.01,
        1,
        2,
        0.1,
        List.of(),
        List.of("placed for the test"));
  }

  /**
   * A short third piece: seam 2's cut can lie no lower than 190 mm on the leg. A hand cut of seam 1
   * at 280 mm, well inside its own overlap, would leave it nowhere to go; seam 1's cut stops at 180
   * mm instead and both stay in order (Astra, 11 Nov 2026). An overlap shorter than two feathers
   * takes its cut at its middle, never outside it.
   */
  @Test
  void aHandCutLeavesRoomForTheCutBelowIt() throws IOException {
    List<Piece> three = StitchFixture.shortThird().pieces();
    for (Piece p : three) {
      p.prepare();
    }
    List<Phantom.Cut> where = StitchFixture.SHORT_THIRD;
    List<Seam> placed =
        List.of(
            at(Phantom.truth(where.get(0), where.get(1))),
            at(Phantom.truth(where.get(1), where.get(2))));
    for (double second : new double[] {1, 70, 95, 400}) {
      Stitch s = new Stitch(three, placed, StitchFixture.CHOICES, new double[] {280, second});
      String both = "seam 1 at %.1f, seam 2 at %.1f".formatted(s.seamY(0), s.seamY(1));
      assertTrue(s.cutsInOrder(), both);
      assertTrue(s.seamY(1) >= s.seamY(0) + Stitch.FEATHER_MM - 1e-6, both);
      assertTrue(s.seamY(1) <= 190 + 1e-6, both + ": inside the short overlap");
      assertTrue(s.seamY(0) <= 180 + 1e-6, both);
    }
    Stitch automaticBelow =
        new Stitch(three, placed, StitchFixture.CHOICES, new double[] {280, Double.NaN});
    assertTrue(automaticBelow.cutsInOrder());
    assertTrue(automaticBelow.seamY(0) <= automaticBelow.seamY(1) - Stitch.FEATHER_MM + 1e-6);

    // an overlap of 12 mm, shorter than two feathers: the cut at its middle
    List<Seam> barely = List.of(at(Rigid.shift(0, 288)), placed.get(1));
    Stitch thin = new Stitch(three, barely, StitchFixture.CHOICES, new double[] {250, Double.NaN});
    assertEquals(294, thin.cut(0).yMm(), 1e-6);
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
