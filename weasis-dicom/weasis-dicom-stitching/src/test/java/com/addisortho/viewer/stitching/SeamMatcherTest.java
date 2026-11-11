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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeamMatcherTest {

  private static final double W = 280;
  private static final double H = 380;

  /** Design section 10: the recovered transform within 0.5 mm and 0.2 degrees. */
  private static void assertRecovers(Rigid truth, Seam seam, double atX, double atY) {
    double[] want = truth.apply(atX, atY);
    double[] got = seam.transform().apply(atX, atY);
    double err = Math.hypot(want[0] - got[0], want[1] - got[1]);
    assertTrue(err < 0.5, "position error %.3f mm; %s".formatted(err, seam.summary()));
    assertEquals(truth.degrees(), seam.transform().degrees(), 0.2, seam.summary());
  }

  @Test
  void recoversShiftRotationAcrossExposureAndSpacing() {
    Phantom leg = Phantom.leg(300, 900, 7);
    var upper = new Phantom.Cut(new Rigid(Math.toRadians(0.4), 8, 0), W, H, 0.15, 1.0, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(-0.9), 14, 315), W, H, 0.175, 1.6, -0.3);
    Seam seam =
        SeamMatcher.match(
            leg.matchImage(upper, 0.02, 1), leg.matchImage(lower, 0.02, 2), SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertEquals(Seam.Method.AUTOMATIC, seam.method());
    assertRecovers(Phantom.truth(upper, lower), seam, W / 2, 30);
    assertTrue(seam.rotationSigmaDeg() < 0.2, seam.summary());
    assertEquals(1.0, seam.scale(), 0.01);
  }

  @Test
  void neverCallsAnAutomaticSeamGood() {
    Phantom leg = Phantom.leg(300, 900, 11);
    var upper = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.2, 1, 0);
    var lower = new Phantom.Cut(Rigid.shift(-5, 300), W, H, 0.2, 1, 0);
    Seam seam =
        SeamMatcher.match(
            leg.matchImage(upper, 0.02, 3), leg.matchImage(lower, 0.02, 4), SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertTrue(seam.reasons().stream().anyMatch(r -> r.contains("not reviewed")));
    assertRecovers(Phantom.truth(upper, lower), seam, W / 2, 40);
  }

  /**
   * A single leg on a 427 mm wide plate (the hospital's DR panel), texture on the bone only and the
   * overlap over the knee: the matched points can only span the bone, so the spread and hold-out
   * gates must be measured against the bone, not the film (the 7 Nov 2026 case).
   */
  @Test
  void matchesASingleLegInAWideFilm() {
    double w = 427;
    double h = 356;
    Phantom leg = Phantom.singleLeg(w, 900, 150, 21);
    var upper = new Phantom.Cut(new Rigid(Math.toRadians(0.3), 0, 140), w, h, 0.139, 1.0, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(-0.4), 3, 420), w, h, 0.139, 1.3, -0.2);
    for (double noise : new double[] {0.02, 0.04}) {
      Seam seam =
          SeamMatcher.match(
              leg.matchImage(upper, noise, 31),
              leg.matchImage(lower, noise, 32),
              SeamMatcher.Band.WHOLE);
      assertEquals(
          Seam.Status.CHECK, seam.status(), noise + ": " + String.join("; ", seam.reasons()));
      assertRecovers(Phantom.truth(upper, lower), seam, 150, 320);
    }
  }

  /** Target leg Both: two legs that moved alike give one seam, and the check says they agree. */
  @Test
  void bothLegsThatAgreeGiveOneSeam() {
    double w = 427;
    double h = 356;
    Phantom legs = Phantom.twoLegs(w, 900, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(0.3), 2, 280), w, h, 0.139, 1.2, 0);
    Seam seam =
        SeamMatcher.matchBothLegs(
            legs.matchImage(upper, 0.02, 43), legs.matchImage(lower, 0.02, 44));
    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertTrue(seam.reasons().get(0).contains("within the limits"), seam.reasons().get(0));
    assertRecovers(Phantom.truth(upper, lower), seam, w / 2, 320);
  }

  /** Target leg Both: the right leg sits 3 mm lower in the second film (parallax): refused. */
  @Test
  void bothLegsThatDisagreeAreRefused() {
    double w = 427;
    double h = 356;
    Phantom legs = Phantom.twoLegs(w, 900, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lowerLeft = new Phantom.Cut(Rigid.shift(2, 280), w, h, 0.139, 1, 0);
    var lowerRight = new Phantom.Cut(Rigid.shift(2, 277), w, h, 0.139, 1, 0);
    Seam seam =
        SeamMatcher.matchBothLegs(
            legs.matchImage(upper, 0.02, 45),
            legs.splicedMatchImage(lowerLeft, lowerRight, 0.02, 46));
    assertEquals(Seam.Status.FAILED, seam.status(), String.join("; ", seam.reasons()));
    assertTrue(seam.reasons().get(0).contains("parallax"), seam.reasons().get(0));
  }

  /**
   * One target leg, but the band left on the whole width of a film of both legs: the legs sit 3 or
   * 4 mm apart in the second film (parallax). One rigid fit of both turns that into a tilt of 0.8
   * to 1 deg that passes the residual and hold-out gates; each half's own points are level, so the
   * seam is refused and keeps no tilt (review of 11 Nov 2026).
   */
  @Test
  void aWholeWidthSeamOnTwoLegsThatDisagreeIsRefused() {
    double w = 427;
    double h = 356;
    Phantom legs = Phantom.twoLegs(w, 900, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lowerLeft = new Phantom.Cut(Rigid.shift(2, 280), w, h, 0.139, 1, 0);
    for (double apart : new double[] {3, 4}) {
      var lowerRight = new Phantom.Cut(Rigid.shift(2, 280 - apart), w, h, 0.139, 1, 0);
      Seam seam =
          SeamMatcher.match(
              legs.matchImage(upper, 0.02, 45),
              legs.splicedMatchImage(lowerLeft, lowerRight, 0.02, 46),
              SeamMatcher.Band.WHOLE);
      assertEquals(Seam.Status.FAILED, seam.status(), apart + " mm: " + seam.summary());
      assertEquals(0, seam.transform().theta(), 0.0, seam.summary());
      assertTrue(
          seam.reasons().stream().anyMatch(r -> r.contains("do not share one seam")),
          seam.reasons().toString());
    }
  }

  private static List<Rigid.Pair> group(Rigid truth, double x, double y0, double dy, int n) {
    List<Rigid.Pair> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      double fx = x + (i % 2) * 3;
      double fy = y0 + i * dy;
      double[] t = truth.apply(fx, fy);
      out.add(new Rigid.Pair(fx, fy, t[0], t[1]));
    }
    return out;
  }

  /**
   * The two sides of the image on their matched points alone (Astra, 11 Nov 2026): five points on
   * each leg, the right leg 4 mm lower, fit a 1.1 deg turn with a tenth of a millimetre left over.
   * Neither group fixes a rotation by itself, so the turn rests on the difference between the legs
   * alone and the seam is refused; missing evidence is not agreement.
   */
  @Test
  void twoSmallGroupsOfPointsCannotVouchForARotation() {
    MatchImage upper = Phantom.leg(427, 400, 21).matchImage(
        new Phantom.Cut(Rigid.IDENTITY, 427, 356, 0.5, 1, 0), 0.01, 30);
    List<Rigid.Pair> pairs = new ArrayList<>(group(Rigid.shift(0, 280), 100, 20, 4, 5));
    pairs.addAll(group(Rigid.shift(0, 284), 300, 20, 4, 5));
    Rigid fit = Rigid.fit(pairs);
    assertTrue(Math.abs(fit.degrees()) > 1.0 && fit.rms(pairs) < 0.3, "the fit looks sound");
    String why = SeamMatcher.halvesDisagree(upper, SeamMatcher.Band.WHOLE, fit, pairs);
    assertNotNull(why);
    assertTrue(why.contains("neither group fixes a rotation"), why);

    // the same two small groups under a level seam: nothing rests on them
    List<Rigid.Pair> level = new ArrayList<>(group(Rigid.shift(0, 280), 100, 20, 4, 5));
    level.addAll(group(Rigid.shift(0, 280.3), 300, 20, 4, 5));
    assertNull(
        SeamMatcher.halvesDisagree(upper, SeamMatcher.Band.WHOLE, Rigid.fit(level), level));

    // one leg, one group of points, however it lies about the middle: not parted, not judged
    List<Rigid.Pair> one = new ArrayList<>();
    Rigid turned = new Rigid(Math.toRadians(1.2), 3, 280);
    for (int i = 0; i < 30; i++) {
      double fx = 180 + (i % 6) * 12;
      double fy = 10 + (i / 6) * 12;
      double[] t = turned.apply(fx, fy);
      one.add(new Rigid.Pair(fx, fy, t[0], t[1]));
    }
    assertNull(SeamMatcher.halvesDisagree(upper, SeamMatcher.Band.WHOLE, Rigid.fit(one), one));

    // two legs that each show the same turn: a film that turned
    List<Rigid.Pair> both = new ArrayList<>();
    for (double x : new double[] {70, 290}) {
      for (int i = 0; i < 30; i++) {
        double fx = x + (i % 6) * 12;
        double fy = 10 + (i / 6) * 12;
        double[] t = turned.apply(fx, fy);
        both.add(new Rigid.Pair(fx, fy, t[0] + (i % 3 - 1) * 0.1, t[1] + (i % 2) * 0.1));
      }
    }
    assertNull(SeamMatcher.halvesDisagree(upper, SeamMatcher.Band.WHOLE, Rigid.fit(both), both));

    // and two legs, each level, one 2 mm lower: both sides fixed, and they disagree
    List<Rigid.Pair> apart = new ArrayList<>();
    for (double[] leg : new double[][] {{70, 280}, {290, 282}}) {
      for (int i = 0; i < 30; i++) {
        double fx = leg[0] + (i % 6) * 12;
        double fy = 10 + (i / 6) * 12;
        apart.add(
            new Rigid.Pair(fx, fy, fx + (i % 3 - 1) * 0.1, fy + leg[1] + (i % 2) * 0.1));
      }
    }
    assertNotNull(
        SeamMatcher.halvesDisagree(upper, SeamMatcher.Band.WHOLE, Rigid.fit(apart), apart));
  }

  /** The same films with the legs moved alike: a film that truly turned is not refused for it. */
  @Test
  void aWholeWidthSeamOnTwoLegsThatTurnedTogetherPasses() {
    double w = 427;
    double h = 356;
    Phantom legs = Phantom.twoLegs(w, 900, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(0.8), 2, 280), w, h, 0.139, 1.2, 0);
    Seam seam =
        SeamMatcher.match(
            legs.matchImage(upper, 0.02, 43),
            legs.matchImage(lower, 0.02, 44),
            SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertRecovers(Phantom.truth(upper, lower), seam, w / 2, 320);
  }

  /**
   * Target leg Both refusing a whole-width seam that had passed on its own: the refused seam keeps
   * no fitted rotation and no residual figures (review of 11 Nov 2026).
   */
  @Test
  void bothLegsRefusedKeepNoTilt() {
    double w = 427;
    double h = 356;
    Phantom legs = Phantom.twoLegs(w, 900, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lowerLeft = new Phantom.Cut(Rigid.shift(2, 280), w, h, 0.139, 1, 0);
    for (double apart : new double[] {2, 3}) {
      var lowerRight = new Phantom.Cut(Rigid.shift(2, 280 - apart), w, h, 0.139, 1, 0);
      Seam seam =
          SeamMatcher.matchBothLegs(
              legs.matchImage(upper, 0.02, 45),
              legs.splicedMatchImage(lowerLeft, lowerRight, 0.02, 46));
      assertEquals(Seam.Status.FAILED, seam.status(), apart + " mm: " + seam.summary());
      assertEquals(0, seam.transform().theta(), 0.0, apart + " mm: " + seam.summary());
      assertTrue(Double.isNaN(seam.rmsMm()), seam.summary());
      assertTrue(Double.isNaN(seam.rotationSigmaDeg()), seam.summary());
    }
  }

  /** The same leg with the overlap on bare shaft, no texture there: still refused. */
  @Test
  void refusesASingleLegShaftOverlap() {
    double w = 427;
    double h = 356;
    Phantom shaft = Phantom.shaftOnly(w, 900);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lower = new Phantom.Cut(Rigid.shift(0, 290), w, h, 0.139, 1, 0);
    Seam seam =
        SeamMatcher.match(
            shaft.matchImage(upper, 0.02, 33),
            shaft.matchImage(lower, 0.02, 34),
            SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.FAILED, seam.status(), seam.summary());
  }

  /**
   * A failed seam is left level: the rough position refined by shift only, never the failed fit's
   * rotation (case LL-001, 7 Nov 2026). The lower piece is cut 0.8 deg askew; the shaft fixes the
   * sideways position, which must be within 1 mm of the truth at the overlap's centre.
   */
  @Test
  void aFailedSeamKeepsNoRotation() {
    Phantom shaft = Phantom.shaftOnly(300, 900);
    var upper = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.2, 1, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(0.8), 2, 320), W, H, 0.2, 1, 0);
    Seam seam =
        SeamMatcher.match(
            shaft.matchImage(upper, 0.01, 71),
            shaft.matchImage(lower, 0.01, 72),
            SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.FAILED, seam.status(), seam.summary());
    assertEquals(0, seam.transform().theta(), 0.0, seam.summary());
    assertTrue(Double.isNaN(seam.rmsMm()), seam.summary());
    double[] want = Phantom.truth(upper, lower).apply(W / 2, 30);
    double[] got = seam.transform().apply(W / 2, 30);
    assertEquals(want[0], got[0], 1.0, seam.summary());
  }

  /**
   * Two legs 3 mm apart in the second film (parallax), matched on the whole width: a rigid fit
   * tilts to meet both. Whether the gates refuse it or not, the seam carries no tilt the gates
   * refused, and a refused seam says so.
   */
  @Test
  void aRefusedFitLeavesNoTilt() {
    double w = 427;
    double h = 356;
    Phantom legs = Phantom.twoLegs(w, 900, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lowerLeft = new Phantom.Cut(Rigid.shift(2, 280), w, h, 0.139, 1, 0);
    var lowerRight = new Phantom.Cut(Rigid.shift(2, 277), w, h, 0.139, 1, 0);
    Seam seam =
        SeamMatcher.match(
            legs.matchImage(upper, 0.02, 45),
            legs.splicedMatchImage(lowerLeft, lowerRight, 0.02, 46),
            SeamMatcher.Band.WHOLE);
    if (seam.status() == Seam.Status.FAILED) {
      assertEquals(0, seam.transform().theta(), 0.0, seam.summary());
      assertTrue(
          seam.reasons().stream().anyMatch(r -> r.contains("keeps no fitted rotation")),
          seam.reasons().toString());
      double[] got = seam.transform().apply(w / 2, 300);
      double[] want = Rigid.shift(2, 278.5).apply(w / 2, 300);
      assertTrue(Math.hypot(got[0] - want[0], got[1] - want[1]) < 2.5, seam.summary());
    } else {
      assertTrue(Math.abs(seam.transform().degrees()) < 1.0, seam.summary());
    }
  }

  /**
   * A refused automatic seam with enough matched points is left where those points agree, by shift
   * alone, never at a rough peak far from them (real case, 10 Nov 2026: the knee-to-ankle seam was
   * left 7 mm above its matched points). Here one leg turned 1.2 deg and sits 2 mm lower; the rough
   * peak of the whole width lies about 300 mm from the matched points.
   */
  @Test
  void aRefusedSeamIsLeftWhereItsMatchedPointsAgree() {
    double w = 427;
    double h = 427;
    Phantom legs = Phantom.twoLegs(w, 1100, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lowerLeft = new Phantom.Cut(Rigid.shift(2, 85), w, h, 0.139, 1, 0);
    var lowerRight = new Phantom.Cut(new Rigid(Math.toRadians(1.2), 2, 87), w, h, 0.139, 1, 0);
    Seam seam =
        SeamMatcher.match(
            legs.matchImage(upper, 0.02, 45),
            legs.splicedMatchImage(lowerLeft, lowerRight, 0.02, 46),
            SeamMatcher.Band.WHOLE);

    assertEquals(Seam.Status.FAILED, seam.status(), seam.summary());
    assertTrue(seam.pairs().size() >= SeamMatcher.MIN_INLIERS, seam.summary());
    assertEquals(0, seam.transform().theta(), 0.0, "still no fitted rotation");
    assertEquals(SeamMatcher.meanShift(seam.pairs()), seam.transform());
    assertTrue(
        seam.reasons().stream().anyMatch(r -> r.contains("average shift of the matched points")),
        seam.reasons().toString());
    // at the matched points' centre the seam carries them where they matched
    double fx = seam.pairs().stream().mapToDouble(Rigid.Pair::fx).average().orElseThrow();
    double fy = seam.pairs().stream().mapToDouble(Rigid.Pair::fy).average().orElseThrow();
    double tx = seam.pairs().stream().mapToDouble(Rigid.Pair::tx).average().orElseThrow();
    double ty = seam.pairs().stream().mapToDouble(Rigid.Pair::ty).average().orElseThrow();
    double[] got = seam.transform().apply(fx, fy);
    assertEquals(tx, got[0], 1e-6);
    assertEquals(ty, got[1], 1e-6);
    // and within a few millimetres of one of the legs' true seams, not hundreds away
    double[] left = Phantom.truth(upper, lowerLeft).apply(fx, fy);
    double[] right = Phantom.truth(upper, lowerRight).apply(fx, fy);
    double off =
        Math.min(
            Math.hypot(got[0] - left[0], got[1] - left[1]),
            Math.hypot(got[0] - right[0], got[1] - right[1]));
    assertTrue(off < 3, "%.1f mm from the nearer leg's seam".formatted(off));
  }

  /**
   * The known limit of that rule (reviews of 10 Nov 2026): on a whole-width match of two legs that
   * moved differently, no single shift suits both, and the refused seam follows the leg its matched
   * points lie on. The other leg is then several millimetres off, the seam stays refused, and
   * "Both" stays refused too. Printed so a change to the matcher shows what it did to the far leg.
   */
  @Test
  void aRefusedWholeWidthSeamFollowsOneLegAndIsOffOnTheOther() {
    double w = 427;
    double h = 427;
    Phantom legs = Phantom.twoLegs(w, 1100, 110, 320, 41);
    var upper = new Phantom.Cut(Rigid.IDENTITY, w, h, 0.139, 1, 0);
    var lowerLeft = new Phantom.Cut(Rigid.shift(2, 85), w, h, 0.139, 1, 0);
    var lowerRight = new Phantom.Cut(new Rigid(Math.toRadians(1.2), 2, 87), w, h, 0.139, 1, 0);
    MatchImage up = legs.matchImage(upper, 0.02, 45);
    MatchImage low = legs.splicedMatchImage(lowerLeft, lowerRight, 0.02, 46);
    Seam seam = SeamMatcher.match(up, low, SeamMatcher.Band.WHOLE);

    assertEquals(Seam.Status.FAILED, seam.status(), seam.summary());
    // each leg's axis, mid-height of the overlap, in the upper piece
    double y = (85 + h) / 2;
    double offLeft = off(seam.transform(), Phantom.truth(upper, lowerLeft), 110, y);
    double offRight = off(seam.transform(), Phantom.truth(upper, lowerRight), 320, y);
    String both = "left %.1f mm, right %.1f mm".formatted(offLeft, offRight);
    assertTrue(Math.min(offLeft, offRight) < 3, both);
    assertTrue(Math.max(offLeft, offRight) < 15, both);
    assertEquals(Seam.Status.FAILED, SeamMatcher.matchBothLegs(up, low).status());
  }

  private static double off(Rigid got, Rigid truth, double x, double y) {
    double[] g = got.apply(x, y);
    double[] t = truth.apply(x, y);
    return Math.hypot(g[0] - t[0], g[1] - t[1]);
  }

  @Test
  void theAverageShiftOfMatchedPointsIsTheirMeanDisplacement() {
    var pairs =
        java.util.List.of(
            new Rigid.Pair(0, 0, 2, 85),
            new Rigid.Pair(10, 0, 12, 86),
            new Rigid.Pair(0, 10, 2, 96));
    Rigid r = SeamMatcher.meanShift(pairs);
    assertEquals(2, r.tx(), 1e-12);
    assertEquals(85 + 2.0 / 3, r.ty(), 1e-12);
    assertEquals(0, r.theta(), 0.0);
  }

  @Test
  void refusesAShaftOnlyOverlap() {
    Phantom shaft = Phantom.shaftOnly(300, 900);
    var upper = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.2, 1, 0);
    var lower = new Phantom.Cut(Rigid.shift(0, 320), W, H, 0.2, 1, 0);
    Seam seam =
        SeamMatcher.match(
            shaft.matchImage(upper, 0.01, 5),
            shaft.matchImage(lower, 0.01, 6),
            SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.FAILED, seam.status(), seam.summary());
  }

  @Test
  void refusesARepeatingPattern() {
    Phantom dots = Phantom.dots(300, 900, 10);
    var upper = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.2, 1, 0);
    var lower = new Phantom.Cut(Rigid.shift(0, 320), W, H, 0.2, 1, 0);
    Seam seam =
        SeamMatcher.match(
            dots.matchImage(upper, 0.01, 7),
            dots.matchImage(lower, 0.01, 8),
            SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.FAILED, seam.status(), seam.summary());
    assertTrue(seam.uniqueness() < SeamMatcher.MIN_UNIQUENESS, seam.summary());
  }

  @Test
  void matchesOnlyInsideTheChosenBand() {
    Phantom leg = Phantom.leg(300, 900, 13);
    var upper = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.2, 1, 0);
    var lower = new Phantom.Cut(Rigid.shift(4, 310), W, H, 0.2, 1, 0);
    Seam seam =
        SeamMatcher.match(
            leg.matchImage(upper, 0.02, 9),
            leg.matchImage(lower, 0.02, 10),
            SeamMatcher.Band.RIGHT_HALF);
    assertTrue(
        seam.pairs().stream().allMatch(p -> p.tx() >= W / 2 - 1e-9),
        "every matched point lies in the right half");
    assertRecovers(Phantom.truth(upper, lower), seam, W * 0.75, 30);
  }

  @Test
  void manualPairsCloseTogetherDoNotPassTheLeftOutCheck() {
    Rigid truth = Rigid.shift(2, 300);
    Seam close = SeamMatcher.manual(clicks(truth, 100, 20, 110, 25, 105, 30));
    assertTrue(close.reasons().get(0).contains("spread them"), close.reasons().toString());
  }

  private static List<Rigid.Pair> clicks(Rigid truth, double... xy) {
    List<Rigid.Pair> out = new ArrayList<>();
    for (int i = 0; i < xy.length; i += 2) {
      double[] q = truth.apply(xy[i], xy[i + 1]);
      out.add(new Rigid.Pair(xy[i], xy[i + 1], q[0], q[1]));
    }
    return out;
  }

  @Test
  void manualSeamNeedsThreeAgreeingPairs() {
    Rigid truth = new Rigid(Math.toRadians(0.8), 3, 310);
    // two pairs fit exactly whatever they are: unconfirmed, failed, cannot be saved (Kimi, 7 Nov)
    Seam two = SeamMatcher.manual(clicks(truth, 40, 20, 220, 30));
    assertEquals(Seam.Status.FAILED, two.status());
    assertEquals(Seam.Method.MANUAL, two.method());
    assertTrue(two.reasons().get(0).contains("three or more"), two.reasons().toString());

    Seam three = SeamMatcher.manual(clicks(truth, 40, 20, 220, 30, 130, 50));
    assertEquals(Seam.Status.CHECK, three.status());
    assertTrue(three.reasons().get(0).contains("agrees"), three.reasons().toString());
    assertEquals(truth.degrees(), three.transform().degrees(), 1e-9);

    List<Rigid.Pair> wrong = clicks(truth, 40, 20, 220, 30, 130, 50);
    Rigid.Pair p = wrong.remove(2);
    wrong.add(new Rigid.Pair(p.fx(), p.fy(), p.tx(), p.ty() + 6));
    assertEquals(Seam.Status.FAILED, SeamMatcher.manual(wrong).status());
  }

  @Test
  void movingASeamByHandMakesItSemiAutomatic() {
    Seam auto =
        new Seam(
            Rigid.shift(0, 300),
            Seam.Method.AUTOMATIC,
            Seam.Status.CHECK,
            0.2,
            0.01,
            0.01,
            1,
            2,
            0.1,
            List.of(),
            List.of("automatic match, not reviewed"));
    Seam moved = auto.adjusted(Rigid.shift(0.1, 300), 100, 20);
    assertEquals(Seam.Method.SEMI_AUTOMATIC, moved.method());
    assertEquals(Seam.Status.CHECK, moved.status());
    Seam failed = Seam.failed(Rigid.shift(0, 300), Seam.Method.AUTOMATIC, List.of("no overlap"));
    assertEquals(Seam.Status.FAILED, failed.adjusted(Rigid.shift(0, 301), 100, 20).status());
    // a turn is measured where the overlap is, not at the piece's corner
    Seam turned = auto.adjusted(new Rigid(Math.toRadians(0.2), 0, 300), 100, 20);
    assertEquals(Seam.Status.CHECK, turned.status(), turned.reasons().toString());
    assertEquals(
        Seam.Status.FAILED, auto.adjusted(new Rigid(Math.toRadians(0.5), 0, 300), 0, 0).status());
    assertEquals(Seam.Status.FAILED, auto.adjusted(Rigid.shift(2.5, 300), 100, 20).status());
  }

  /**
   * The radiographer moved the detector only 84 mm between the knee and the ankle (the 7 Nov 2026
   * case): an overlap of 0.8 of the piece, far past the old 150 mm cap.
   */
  @Test
  void matchesANearlyCompleteOverlap() {
    double w = 427;
    double h = 356;
    Phantom leg = Phantom.singleLeg(w, 900, 150, 51);
    var upper = new Phantom.Cut(Rigid.shift(0, 200), w, h, 0.139, 1.0, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(0.2), 2, 271), w, h, 0.139, 1.2, -0.1);
    Seam seam =
        SeamMatcher.match(
            leg.matchImage(upper, 0.02, 52),
            leg.matchImage(lower, 0.02, 53),
            SeamMatcher.Band.WHOLE);
    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertRecovers(Phantom.truth(upper, lower), seam, 150, 250);
  }

  /** A seam placed by hand 6 mm and half a degree off, then refined: recovered, semi-automatic. */
  @Test
  void refinesFromAHandSetPosition() {
    Phantom leg = Phantom.leg(300, 900, 7);
    var upper = new Phantom.Cut(Rigid.IDENTITY, W, H, 0.15, 1.0, 0);
    var lower = new Phantom.Cut(new Rigid(Math.toRadians(-0.5), 6, 310), W, H, 0.15, 1.3, 0);
    Rigid truth = Phantom.truth(upper, lower);
    Rigid hand = new Rigid(truth.theta() + Math.toRadians(0.5), truth.tx() + 4, truth.ty() - 5);
    Seam seam =
        SeamMatcher.refine(
            leg.matchImage(upper, 0.02, 61),
            leg.matchImage(lower, 0.02, 62),
            SeamMatcher.Band.WHOLE,
            hand);
    assertEquals(Seam.Status.CHECK, seam.status(), String.join("; ", seam.reasons()));
    assertEquals(Seam.Method.SEMI_AUTOMATIC, seam.method());
    assertTrue(seam.reasons().stream().anyMatch(r -> r.contains("set by hand")), seam.summary());
    assertRecovers(truth, seam, W / 2, 30);
  }
}
