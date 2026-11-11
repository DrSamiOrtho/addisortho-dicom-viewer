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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Target leg Both on seams set by hand (Sami, 10 Nov 2026): Refine automatically from here and Fit
 * to the points are held to both legs, as Line up already was.
 */
class BothLegsHandSeamTest {

  private static final double W = 427;
  private static final double H = 356;

  private static MatchImage upper;
  private static MatchImage agreeing;
  private static MatchImage disagreeing;
  private static Rigid truth;

  @BeforeAll
  static void build() {
    Phantom legs = Phantom.twoLegs(W, 900, 110, 320, 41);
    Phantom.Cut up = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.139, 1, 0);
    Phantom.Cut low = new Phantom.Cut(new Rigid(Math.toRadians(0.3), 2, 280), W, H, 0.139, 1.2, 0);
    upper = legs.matchImage(up, 0.02, 43);
    agreeing = legs.matchImage(low, 0.02, 44);
    truth = Phantom.truth(up, low);
    // the right leg sits 3 mm higher in the second film than the left: parallax or movement
    disagreeing =
        legs.splicedMatchImage(
            new Phantom.Cut(Rigid.shift(2, 280), W, H, 0.139, 1, 0),
            new Phantom.Cut(Rigid.shift(2, 277), W, H, 0.139, 1, 0),
            0.02,
            46);
  }

  private static Rigid.Pair pair(Rigid t, double x, double y) {
    double[] to = t.apply(x, y);
    return new Rigid.Pair(x, y, to[0], to[1]);
  }

  @Test
  void refiningLegsThatAgreeGivesOneSeamAndSaysTheyAgree() {
    Rigid byHand = Rigid.shift(1.0, -0.8).after(truth);

    Seam seam = SeamMatcher.refineBothLegs(upper, agreeing, byHand);

    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertEquals(Seam.Method.SEMI_AUTOMATIC, seam.method());
    assertTrue(seam.reasons().get(0).contains("within the limits"), seam.reasons().get(0));
    assertTrue(StitchResume.apart(seam.transform(), truth, W, H) < 1, "it finds the seam");
    assertEquals(seam, SeamMatcher.refineBothLegs(upper, agreeing, byHand), "and always the same");
  }

  @Test
  void refiningLegsThatDisagreeFailsAndLeavesTheSeamWhereItWasPut() {
    Rigid byHand = Rigid.shift(2, 278.5);

    Seam seam = SeamMatcher.refineBothLegs(upper, disagreeing, byHand);

    assertEquals(Seam.Status.FAILED, seam.status(), String.join("; ", seam.reasons()));
    assertEquals(byHand, seam.transform(), "no refused fit moves the piece");
    assertTrue(Double.isNaN(seam.rmsMm()));
    assertTrue(
        seam.reasons().get(0).contains("parallax")
            || seam.reasons().get(0).contains("one at a time"),
        seam.reasons().get(0));
    // the gap this closes: on the whole width alone, the same film refines to "check"
    Seam plain = SeamMatcher.refine(upper, disagreeing, SeamMatcher.Band.WHOLE, byHand);
    assertEquals(Seam.Status.CHECK, plain.status(), String.join("; ", plain.reasons()));
  }

  @Test
  void bothNeverPassesWhatThePlainRulesRefuse() {
    // a refine the whole width refuses stays refused, wherever it starts
    Rigid far = Rigid.shift(2, 240);
    Seam plain = SeamMatcher.refine(upper, agreeing, SeamMatcher.Band.WHOLE, far);
    Seam both = SeamMatcher.refineBothLegs(upper, agreeing, far);
    if (plain.status() == Seam.Status.FAILED) {
      assertEquals(Seam.Status.FAILED, both.status());
      assertEquals(far, both.transform());
    }
    // point pairs the ordinary rule refuses stay refused when they lie on both legs
    List<Rigid.Pair> two = List.of(pair(truth, 100, 20), pair(truth, 320, 30));
    assertEquals(Seam.Status.FAILED, SeamMatcher.manual(two).status());
    assertEquals(Seam.Status.FAILED, SeamMatcher.manualBothLegs(two, W).status());
    assertEquals(SeamMatcher.manual(two), SeamMatcher.manualBothLegs(two, W));
  }

  @Test
  void pointPairsOnOneLegDoNotConfirmASeamForBoth() {
    List<Rigid.Pair> left =
        List.of(pair(truth, 90, 15), pair(truth, 125, 30), pair(truth, 105, 60));
    assertEquals(Seam.Status.CHECK, SeamMatcher.manual(left).status(), "enough for one leg");

    Seam oneLeg = SeamMatcher.manualBothLegs(left, W);
    assertEquals(Seam.Status.FAILED, oneLeg.status());
    assertTrue(oneLeg.reasons().get(0).contains("left half"), oneLeg.reasons().get(0));
    assertEquals(SeamMatcher.manual(left).transform(), oneLeg.transform(), "the fit is the same");
    assertEquals(Seam.Method.MANUAL, oneLeg.method());

    List<Rigid.Pair> right =
        List.of(pair(truth, 300, 15), pair(truth, 335, 30), pair(truth, 320, 60));
    assertTrue(SeamMatcher.manualBothLegs(right, W).reasons().get(0).contains("right half"));

    List<Rigid.Pair> both = new ArrayList<>(left);
    both.add(pair(truth, 320, 25));
    Seam held = SeamMatcher.manualBothLegs(both, W);
    assertEquals(Seam.Status.CHECK, held.status(), String.join("; ", held.reasons()));
    assertEquals("the point pairs lie on both legs", held.reasons().get(0));

    // a pair on the other leg that does not agree with the first leg's fails the left-out check
    List<Rigid.Pair> moved = new ArrayList<>(left);
    double[] to = truth.apply(320, 25);
    moved.add(new Rigid.Pair(320, 25, to[0], to[1] - 3));
    Seam apart = SeamMatcher.manualBothLegs(moved, W);
    assertEquals(Seam.Status.FAILED, apart.status());
    assertTrue(apart.reasons().get(0).contains("disagrees"), apart.reasons().get(0));
  }

  @Test
  void theDecisionFollowsTheLegTheSeamsAreMatchedFor() {
    SeamMatcher.Band whole = SeamMatcher.Band.WHOLE;
    List<Rigid.Pair> pairs = List.of(pair(truth, 90, 15));
    Rigid start = Rigid.shift(2, 280);

    assertEquals(
        SeamRecipe.refineBothLegs(start), SeamRecipe.matchBothLegs().refineFrom(whole, start));
    assertEquals(SeamRecipe.pointsBothLegs(pairs), SeamRecipe.matchBothLegs().fitTo(pairs));
    assertEquals(SeamRecipe.refine(whole, start), SeamRecipe.match(whole).refineFrom(whole, start));
    assertEquals(SeamRecipe.points(pairs), SeamRecipe.match(whole).fitTo(pairs));
    // once leg by leg, a later refine or fit stays leg by leg
    assertEquals(
        SeamRecipe.Kind.POINTS_BOTH_LEGS, SeamRecipe.refineBothLegs(start).fitTo(pairs).kind());

    assertEquals(
        SeamMatcher.refineBothLegs(upper, agreeing, start),
        SeamRecipe.refineBothLegs(start).compute(upper, agreeing));
    assertEquals(
        SeamMatcher.manualBothLegs(pairs, W),
        SeamRecipe.pointsBothLegs(pairs).compute(upper, agreeing));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.REFINE_BOTH_LEGS, whole, start, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.REFINE_BOTH_LEGS, null, null, List.of(), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.POINTS_BOTH_LEGS, null, null, List.of(), null));
  }

  @Test
  void handSeamsForBothLegsAreSavedAndReopenedAsTheyWere() throws IOException {
    StitchFixture fixture = StitchFixture.twoLegs();
    List<Piece> pieces = fixture.pieces();
    Seam matched = StitchFixture.seams(pieces, List.of(SeamRecipe.matchBothLegs())).get(0);
    Rigid t = matched.transform();
    List<SeamRecipe> decisions =
        List.of(
            SeamRecipe.refineBothLegs(Rigid.shift(0.6, 0).after(t)),
            SeamRecipe.pointsBothLegs(
                List.of(pair(t, 95, 20), pair(t, 120, 70), pair(t, 310, 25), pair(t, 330, 60))));
    Stitch.Choices both =
        new Stitch.Choices("AP", "standing", Stitch.Leg.BOTH, SeamMatcher.Band.WHOLE);
    for (SeamRecipe decision : decisions) {
      List<Seam> seams = StitchFixture.seams(pieces, List.of(decision));
      assertEquals(
          Seam.Status.CHECK, seams.get(0).status(), () -> seams.get(0).reasons().toString());
      StitchProject saved =
          StitchProjectIO.read(
              StitchProjectIO.write(
                  StitchFixture.capture(
                      new Stitch(pieces, seams, both),
                      List.of(decision),
                      StitchResume.Lineage.NEW)));
      assertEquals(decision, saved.seams().get(0).decision());

      StitchResume.Resumed r =
          StitchResume.recheck(
              saved, StitchResume.sources(saved, fixture.pieces()), (d, n) -> true);

      assertEquals(seams, r.seams());
      assertEquals(List.of(decision), r.recipes());
      assertEquals(List.of(), r.notes());
    }
  }
}
