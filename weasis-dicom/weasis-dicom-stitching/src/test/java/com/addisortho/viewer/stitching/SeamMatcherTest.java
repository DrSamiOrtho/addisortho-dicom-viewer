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
    Seam two = SeamMatcher.manual(clicks(truth, 40, 20, 220, 30));
    assertEquals(Seam.Status.CHECK, two.status());
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
    Seam moved = auto.adjusted(Rigid.shift(0.1, 300));
    assertEquals(Seam.Method.SEMI_AUTOMATIC, moved.method());
    assertEquals(Seam.Status.CHECK, moved.status());
    Seam failed = Seam.failed(Rigid.shift(0, 300), Seam.Method.AUTOMATIC, List.of("no overlap"));
    assertEquals(Seam.Status.FAILED, failed.adjusted(Rigid.shift(0, 301)).status());
  }
}
