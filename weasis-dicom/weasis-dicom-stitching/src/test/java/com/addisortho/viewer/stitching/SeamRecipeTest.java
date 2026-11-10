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
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SeamRecipeTest {

  private static MatchImage upper;
  private static MatchImage lower;

  @BeforeAll
  static void build() throws IOException {
    List<Piece> pieces = new StitchFixture().pieces();
    pieces.get(0).prepare();
    pieces.get(1).prepare();
    upper = pieces.get(0).match();
    lower = pieces.get(1).match();
  }

  @Test
  void theSameDecisionOnTheSamePiecesGivesTheSameSeam() {
    SeamRecipe match = SeamRecipe.match(SeamMatcher.Band.WHOLE);
    Seam once = match.compute(upper, lower);
    assertEquals(once, match.compute(upper, lower));
    assertEquals(SeamMatcher.match(upper, lower, SeamMatcher.Band.WHOLE), once);
    assertEquals(Seam.Method.AUTOMATIC, once.method());

    SeamRecipe refine = SeamRecipe.refine(SeamMatcher.Band.WHOLE, once.transform());
    assertEquals(refine.compute(upper, lower), refine.compute(upper, lower));
    assertEquals(
        SeamMatcher.matchBothLegs(upper, lower), SeamRecipe.matchBothLegs().compute(upper, lower));
  }

  @Test
  void aSeamMovedByHandStaysWhereItWasPutHoweverOftenItWasMoved() {
    SeamRecipe match = SeamRecipe.match(SeamMatcher.Band.WHOLE);
    Seam matched = match.compute(upper, lower);
    Rigid first = Rigid.shift(0.4, 0).after(matched.transform());
    Rigid last = Rigid.shift(0, -0.3).after(first);

    // the window adjusts the seam at every nudge; the decision keeps only where it ended
    Seam nudgedTwice = matched.adjusted(first).adjusted(last);
    Seam made = match.moved(first).moved(last).compute(upper, lower);

    assertEquals(nudgedTwice, made);
    assertEquals(last, made.transform());
    assertEquals(Seam.Method.SEMI_AUTOMATIC, made.method());
    assertEquals(matched.status(), made.status(), "a hand move neither passes nor fails a seam");
    assertTrue(Double.isNaN(made.rmsMm()), "the residual of the old position is not carried over");
    assertTrue(Double.isNaN(made.rotationSigmaDeg()));
    assertTrue(made.pairs().isEmpty());
  }

  @Test
  void pickedPointsAreFittedAndJudgedAsInTheWindow() {
    Rigid t = SeamRecipe.match(SeamMatcher.Band.WHOLE).compute(upper, lower).transform();
    List<Rigid.Pair> pairs =
        List.of(pair(t, 40, 10), pair(t, 110, 25), pair(t, 160, 12), pair(t, 80, 40));

    Seam made = SeamRecipe.points(pairs).compute(null, null);

    assertEquals(SeamMatcher.manual(pairs), made);
    assertEquals(Seam.Method.MANUAL, made.method());
    assertEquals(Seam.Status.CHECK, made.status());
    // two pairs confirm nothing, as before
    assertEquals(
        Seam.Status.FAILED, SeamRecipe.points(pairs.subList(0, 2)).compute(null, null).status());
    // moved by hand afterwards it is still a manual seam
    assertEquals(
        Seam.Method.MANUAL,
        SeamRecipe.points(pairs).moved(Rigid.shift(1, 0).after(t)).compute(null, null).method());
  }

  @Test
  void aDecisionWithTheWrongPartsIsRefused() {
    Rigid r = Rigid.IDENTITY;
    SeamMatcher.Band whole = SeamMatcher.Band.WHOLE;
    List<Rigid.Pair> one = List.of(new Rigid.Pair(0, 0, 1, 1));
    List<Rigid.Pair> none = List.of();
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.MATCH, null, null, none, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.MATCH, whole, r, none, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.REFINE, whole, null, none, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.POINTS, null, null, none, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.MATCH_BOTH_LEGS, whole, null, none, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new SeamRecipe(SeamRecipe.Kind.MATCH, whole, null, one, null));
  }

  private static Rigid.Pair pair(Rigid t, double x, double y) {
    double[] to = t.apply(x, y);
    return new Rigid.Pair(x, y, to[0], to[1]);
  }
}
