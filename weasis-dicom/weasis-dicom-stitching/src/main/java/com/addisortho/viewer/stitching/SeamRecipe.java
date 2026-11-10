/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.util.List;

/**
 * How a seam was set: the decision, from which {@link #compute} makes the {@link Seam} again
 * through the same {@link SeamMatcher} calls and gates the window uses. A saved project keeps this,
 * so a reopened seam is measured afresh instead of being believed (see {@link StitchProject}).
 *
 * @param kind which match made the seam
 * @param band the columns matched on; null for {@link Kind#MATCH_BOTH_LEGS} and {@link Kind#POINTS}
 * @param start the position set by hand that {@link Kind#REFINE} started from, else null
 * @param points the pairs the user picked for {@link Kind#POINTS}, else empty
 * @param movedTo where the user then put the lower piece by hand (drag, nudge, turn), or null: the
 *     seam is the match's seam {@linkplain Seam#adjusted adjusted} to this, so it stays where the
 *     user put it and a failed seam stays failed
 */
record SeamRecipe(
    Kind kind, SeamMatcher.Band band, Rigid start, List<Rigid.Pair> points, Rigid movedTo) {

  /** The match that made the seam. */
  enum Kind {
    /** {@link SeamMatcher#match} on a band. */
    MATCH,
    /** {@link SeamMatcher#matchBothLegs}: target leg Both on the whole width. */
    MATCH_BOTH_LEGS,
    /** {@link SeamMatcher#refine} from a position set by hand. */
    REFINE,
    /** {@link SeamMatcher#manual} on picked point pairs. */
    POINTS
  }

  /** A seam together with the decision that made it. */
  record Made(SeamRecipe recipe, Seam seam) {}

  SeamRecipe {
    points = List.copyOf(points);
    boolean wantsBand = kind == Kind.MATCH || kind == Kind.REFINE;
    if (wantsBand != (band != null)
        || (kind == Kind.REFINE) != (start != null)
        || (kind == Kind.POINTS) == points.isEmpty()) {
      throw new IllegalArgumentException("a " + kind + " seam decision with the wrong parts");
    }
  }

  static SeamRecipe match(SeamMatcher.Band band) {
    return new SeamRecipe(Kind.MATCH, band, null, List.of(), null);
  }

  static SeamRecipe matchBothLegs() {
    return new SeamRecipe(Kind.MATCH_BOTH_LEGS, null, null, List.of(), null);
  }

  static SeamRecipe refine(SeamMatcher.Band band, Rigid start) {
    return new SeamRecipe(Kind.REFINE, band, start, List.of(), null);
  }

  static SeamRecipe points(List<Rigid.Pair> pairs) {
    return new SeamRecipe(Kind.POINTS, null, null, pairs, null);
  }

  /** The same match, with the lower piece then put at {@code to} by hand. */
  SeamRecipe moved(Rigid to) {
    return new SeamRecipe(kind, band, start, points, to);
  }

  /** Makes the seam again; the same pieces give the same seam. */
  Seam compute(MatchImage upper, MatchImage lower) {
    Seam matched =
        switch (kind) {
          case MATCH -> SeamMatcher.match(upper, lower, band);
          case MATCH_BOTH_LEGS -> SeamMatcher.matchBothLegs(upper, lower);
          case REFINE -> SeamMatcher.refine(upper, lower, band, start);
          case POINTS -> SeamMatcher.manual(points);
        };
    return movedTo == null ? matched : matched.adjusted(movedTo);
  }
}
