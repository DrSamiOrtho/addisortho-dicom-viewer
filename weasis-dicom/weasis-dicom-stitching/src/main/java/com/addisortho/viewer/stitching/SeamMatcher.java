/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The automatic overlap match of design section 5.2, for one pair of neighbouring pieces: a coarse
 * correlation search on gradient magnitude, then textured patches matched on the 1 mm intensity and
 * a RANSAC rigid fit, then the gates. Every seam it returns is "check" or "failed"; none is "good"
 * until the thresholds are measured on real cases (design section 10).
 */
final class SeamMatcher {

  static final double MIN_OVERLAP_MM = 15;

  /**
   * The overlap may be nearly the whole piece: on the 7 Nov 2026 four-piece case the radiographer
   * moved the detector only 84 mm between the knee and ankle exposures, an overlap of 343 mm of
   * 427. The earlier 150 mm cap made that seam impossible. The rival-candidate check and the gates
   * guard the larger search.
   */
  static final double MAX_OVERLAP_FRACTION = 0.85;

  static final double MAX_SIDE_SHIFT_MM = 40;
  static final double MAX_ROTATION_DEG = 3;

  // gates (provisional, to be tuned on the 30-case study)
  static final double MIN_UNIQUENESS = 1.15;
  static final double SLIDE_SCORE = 0.05;

  /**
   * Rough positions refined on bone (Kimi review, 7 Nov 2026): an ambiguous rough match is settled
   * by the fine match when one candidate has clearly more agreeing points; it still fails when a
   * rival candidate more than {@link #RIVAL_MM} away has nearly as many.
   */
  static final int CANDIDATES = 3;

  static final double RIVAL_SHARE = 0.7;
  static final double RIVAL_MM = 2.0;
  static final double MIN_EDGE_RATIO = 0.08;
  static final int MIN_INLIERS = 6;
  static final double MAX_RMS_MM = 1.5;

  /**
   * The median residual of the held-back patches (was the worst one: a single held patch on a skin
   * fold or on the other leg, which parallax moves by a few mm, failed seams that 50 or more bone
   * patches supported). The worst is still reported.
   */
  static final double MAX_HOLDOUT_MM = 2.0;

  static final double SCALE_TOLERANCE = 0.01;
  // Kept at 0.15 (Kimi review, 7 Nov 2026): denser patches give a single leg enough points. Fable
  // argued for 0.3 (2 mm at the far end of a 400 mm segment); retune only on real cases.
  static final double MAX_ROTATION_SIGMA_DEG = 0.15;
  static final double MIN_SPREAD = 0.4;
  static final double MIN_SPREAD_MM = 20;
  static final int MIN_HELD = 3;
  static final double MIN_MANUAL_SPREAD_MM = 30;

  private static final int PATCH = 15; // 15 mm at 1 mm
  private static final int STEP = 4;
  private static final int SEARCH = 10; // +/- 10 mm: coarse grid and a 1 deg turn 200 mm off-centre
  // 300, not 120: the 1 mm grid with a 4 mm step offers many more candidates, and on a real knee
  // the strongest 120 lay on one condyle (7 Nov 2026 case)
  private static final int MAX_PATCHES = 300;
  // every tenth patch by strength is held back from the fit and checks it; fitted patches within
  // this many patches of a held one leave the fit
  private static final int HOLD_EVERY = 10;
  private static final double HELD_EXCLUDE = 0.5;
  // Refine from a hand-set position: rough peaks this far around it are refined too, as rivals
  static final double LOCAL_MM = 25;
  // unchanged (Kimi review); the ridge test in matchPatch rejects a patch that could slide
  private static final double MIN_PATCH_NCC = 0.6;
  private static final double MIN_PATCH_ISOTROPY = 0.15;
  private static final double RIDGE_SCORE = 0.03;
  private static final int RIDGE_PX = 2; // 2 mm

  /**
   * 1.5 mm, the same as {@link #MAX_RMS_MM} (was 1.0): in the DRGEM machine's own stitch of the 7
   * Nov 2026 case the two legs sit 2 to 3 mm apart vertically (parallax between exposures), so true
   * pairs on both legs cannot all agree within 1 mm of one rigid fit.
   */
  private static final double INLIER_MM = 1.5;

  private static final int MIN_COARSE_CELLS = 150;
  private static final int REGIONS = 3;

  private SeamMatcher() {}

  /**
   * The columns of the image the match may use, as fractions of each piece's width, as the image is
   * shown: the user's choice, so a bilateral film is matched on the target leg only and never on
   * the other leg (Astra, code review P1).
   */
  record Band(double from, double to, String words) {
    static final Band WHOLE = new Band(0, 1, "whole image width");
    static final Band LEFT_HALF = new Band(0, 0.5, "left half of the image");
    static final Band RIGHT_HALF = new Band(0.5, 1, "right half of the image");

    boolean contains(double xMm, double widthMm) {
      double f = xMm / widthMm;
      return f >= from && f <= to;
    }

    @Override
    public String toString() {
      return words;
    }
  }

  /** Matches {@code lower} under {@code upper}; the seam maps lower millimetres to upper ones. */
  static Seam match(MatchImage upper, MatchImage lower, Band band) {
    List<Coarse> peaks = coarsePeaks(upper, lower, band, CANDIDATES);
    if (peaks.isEmpty()) {
      return Seam.failed(
          Rigid.IDENTITY,
          Seam.Method.AUTOMATIC,
          List.of("no overlap with enough structure was found between these two pieces"));
    }
    Coarse coarse = peaks.get(0);
    List<Fine> fines = new ArrayList<>();
    for (Coarse c : peaks) {
      fines.add(fine(upper, lower, band, Rigid.shift(c.dx, c.dy)));
    }
    Fine chosen = fines.get(0);
    // the rough peak the chosen fine match started from: a failed seam is left at this one, not at
    // the best rough peak when the fine match rejected that for another position (review of 11 Nov
    // 2026)
    Coarse chosenRough = coarse;
    // a candidate that lands on the same seam as the best rough peak is the same answer, not a
    // better one; only a different position with more agreeing points replaces it
    for (int i = 0; i < fines.size(); i++) {
      Fine f = fines.get(i);
      if (f.inliers.size() > chosen.inliers.size()
          && (chosen.inliers.size() < 2 || differs(chosen.fit, f.fit, upper, lower) > RIVAL_MM)) {
        chosen = f;
        chosenRough = peaks.get(i);
      }
    }
    int rivalPoints = 0;
    boolean rival = false;
    for (Fine f : fines) {
      if (f == chosen
          || f.inliers.size() < 2
          || differs(chosen.fit, f.fit, upper, lower) <= RIVAL_MM) {
        continue;
      }
      rivalPoints = Math.max(rivalPoints, f.inliers.size());
      rival |=
          f.inliers.size() >= Math.max(MIN_INLIERS, Math.ceil(RIVAL_SHARE * chosen.inliers.size()));
    }
    List<String> fail = new ArrayList<>();
    List<String> check = new ArrayList<>();
    boolean roughAmbiguous = coarse.uniqueness < MIN_UNIQUENESS;
    if (rival || (roughAmbiguous && chosen.inliers.size() < MIN_INLIERS)) {
      fail.add(
          String.format(
              Locale.ROOT,
              "the overlap matches almost as well at another position (uniqueness %.2f; %d"
                  + " agreeing points there against %d here): a repeating pattern such as ruler"
                  + " ticks, or too little structure",
              coarse.uniqueness,
              rivalPoints,
              chosen.inliers.size()));
    } else if (roughAmbiguous) {
      check.add(
          String.format(
              Locale.ROOT,
              "the rough match was ambiguous (uniqueness %.2f); the fine match on structure chose"
                  + " one position with %d agreeing points, at most %d at the other candidates",
              coarse.uniqueness,
              chosen.inliers.size(),
              rivalPoints));
    }
    if (coarse.slideYMm > 4 || coarse.slideXMm > 4) {
      check.add(
          String.format(
              Locale.ROOT,
              "the rough match could slide %.0f mm up or down and %.0f mm sideways; the fine match"
                  + " settles it",
              coarse.slideYMm,
              coarse.slideXMm));
    }
    return judge(
        upper,
        lower,
        band,
        chosen,
        levelShift(upper, lower, band, chosenRough),
        coarse.uniqueness,
        fail,
        check,
        Seam.Method.AUTOMATIC);
  }

  /** The rough position is refined by shift only within this many mm, on the 1 mm intensity. */
  static final double LEVEL_MM = 2;

  /**
   * The rough position refined by shift alone: the 1 mm intensity of the overlap correlated at
   * every whole-millimetre shift within {@link #LEVEL_MM} of the 2 mm rough peak, then the peak
   * read to a fraction of a millimetre. No rotation. A failed automatic seam with fewer than {@link
   * #MIN_INLIERS} matched points is left here (case LL-001, 7 Nov 2026: the 2 mm grid alone put the
   * femoral cortex 1.6 mm off); with more it is left at {@link #meanShift} (stitch 0.6).
   */
  static Rigid levelShift(MatchImage upper, MatchImage lower, Band band, Coarse coarse) {
    Gray u = upper.fine();
    Gray l = lower.fine();
    double g = MatchImage.FINE_MM;
    int cx = (int) Math.round(coarse.dx / g);
    int cy = (int) Math.round(coarse.dy / g);
    int r = (int) Math.round(LEVEL_MM / g);
    int n = 2 * r + 1;
    double[] score = new double[n * n];
    for (int j = 0; j < n; j++) {
      for (int i = 0; i < n; i++) {
        score[j * n + i] = ncc(u, l, cx + i - r, cy + j - r, band);
      }
    }
    int best = argmax(score);
    if (best < 0 || !Double.isFinite(score[best])) {
      return Rigid.shift(coarse.dx, coarse.dy);
    }
    int bi = best % n;
    int bj = best / n;
    double sx = bi > 0 && bi < n - 1 ? parabola(score[best - 1], score[best], score[best + 1]) : 0;
    double sy = bj > 0 && bj < n - 1 ? parabola(score[best - n], score[best], score[best + n]) : 0;
    return Rigid.shift((cx + bi + sx - r) * g, (cy + bj + sy - r) * g);
  }

  /**
   * The fine match from a position the user set by hand (design section 5.4): no rough search and
   * no rival check, since the user settled where the pieces meet; the fit's own gates decide, so a
   * seam placed by hand can become "check" with a measured residual and uncertainty, or fail.
   */
  static Seam refine(MatchImage upper, MatchImage lower, Band band, Rigid start) {
    List<String> fail = new ArrayList<>();
    List<String> check = new ArrayList<>();
    check.add("refined by the automatic match from a position set by hand");
    Fine chosen = fine(upper, lower, band, start);
    // the hand-set position limits the search; it does not say which repeated structure the user
    // chose, so the rough peaks around it are refined too and a rival fails the seam (Kimi, second
    // review)
    int rivalPoints = 0;
    boolean rival = false;
    for (Coarse c : coarsePeaks(upper, lower, band, CANDIDATES, start, LOCAL_MM)) {
      Fine f = fine(upper, lower, band, Rigid.shift(c.dx, c.dy));
      if (f.inliers.size() < 2 || differs(chosen.fit, f.fit, upper, lower) <= RIVAL_MM) {
        continue;
      }
      rivalPoints = Math.max(rivalPoints, f.inliers.size());
      rival |=
          f.inliers.size() >= Math.max(MIN_INLIERS, Math.ceil(RIVAL_SHARE * chosen.inliers.size()));
    }
    if (rival) {
      fail.add(
          String.format(
              Locale.ROOT,
              "another position within %.0f mm of the one set by hand matches almost as well (%d"
                  + " agreeing points there against %d here): a repeating pattern, or two layers"
                  + " at different depths; check which structure is lined up",
              LOCAL_MM,
              rivalPoints,
              chosen.inliers.size()));
    }
    return judge(
        upper, lower, band, chosen, start, Double.NaN, fail, check, Seam.Method.SEMI_AUTOMATIC);
  }

  /**
   * The gates on a fine match. {@code rough} is the rough position refined by shift only, or the
   * position set by hand. A failed seam is left there, except an automatic one with at least {@link
   * #MIN_INLIERS} matched points, which is left at their {@link #meanShift} (stitch 0.6, 10 Nov
   * 2026); never at the failed fit. A fit the gates refused must not tilt the picture: on case
   * LL-001 (7 Nov 2026) a -1.17 deg fit, parallax absorbed as rotation, failed its hold-out and
   * still turned the tibiae in the saved image.
   */
  private static Seam judge(
      MatchImage upper,
      MatchImage lower,
      Band band,
      Fine chosen,
      Rigid rough,
      double uniqueness,
      List<String> fail,
      List<String> check,
      Seam.Method method) {
    double edgeRatio = edgeRatio(upper.coarse(), chosen.start, lower, band);
    // straight shafts only: a fail unless the fine match found plenty of points, each of which
    // passed the ridge test and so is fixed in both directions (the 343 mm knee-to-ankle overlap
    // of the 7 Nov 2026 case: 45 to 90 such points on tibial structure, yet the shafts dominate
    // the edge directions)
    if (edgeRatio < MIN_EDGE_RATIO) {
      (chosen.inliers.size() < 2 * MIN_INLIERS ? fail : check)
          .add(
              "the overlap has edges in one direction only (straight shaft or ruler): the shift"
                  + " along the bone is "
                  + (chosen.inliers.size() < 2 * MIN_INLIERS
                      ? "not determined. Pick matching points on something that is not a straight"
                          + " line: a ruler numeral, a nutrient foramen, the other leg, a skin"
                          + " crease"
                      : "fixed only by the %d matched points; inspect the overlap"
                          .formatted(chosen.inliers.size())));
    }

    double[] extent = new double[2];
    List<Rigid.Pair> pairs = chosen.pairs;
    List<Rigid.Pair> heldPairs = chosen.heldPairs;
    if (pairs.size() < 2) {
      fail.add("too few matching points on structure in the overlap");
      Seam f = Seam.failed(rough, method, fail);
      return new Seam(
          rough,
          f.method(),
          f.status(),
          f.rmsMm(),
          f.rotationSigmaDeg(),
          f.shiftSigmaMm(),
          f.scale(),
          uniqueness,
          f.holdoutMm(),
          List.of(),
          fail);
    }
    List<Rigid.Pair> inliers = chosen.inliers;
    Rigid fit = chosen.fit;
    double rms = fit.rms(inliers);
    double[] sigma = Rigid.uncertainty(fit, inliers);
    double scale = Rigid.similarityScale(inliers);
    double[] heldRes = heldPairs.stream().mapToDouble(fit::residual).sorted().toArray();
    double holdout = heldRes.length == 0 ? Double.NaN : heldRes[heldRes.length / 2];
    double quarterHeld = heldRes.length == 0 ? Double.NaN : heldRes[3 * heldRes.length / 4];
    double worstHeld = heldRes.length == 0 ? Double.NaN : heldRes[heldRes.length - 1];
    // spread is judged against where matches were found (the bone, side to side), not the film's
    // width: one leg on a 427 mm plate can never span 40 % of the plate (7 Nov 2026 case). Noise
    // patches in soft tissue do not match, so they cannot widen it.
    extent[0] = pairs.stream().mapToDouble(Rigid.Pair::tx).min().orElse(0);
    extent[1] = pairs.stream().mapToDouble(Rigid.Pair::tx).max().orElse(0);
    double width = Math.max(1e-6, extent[1] - extent[0]);
    double lo = inliers.stream().mapToDouble(Rigid.Pair::tx).min().orElse(0);
    double hi = inliers.stream().mapToDouble(Rigid.Pair::tx).max().orElse(0);
    long thirds =
        inliers.stream()
            .mapToInt(q -> (int) Math.min(2, Math.max(0, (q.tx() - extent[0]) * 3 / width)))
            .distinct()
            .count();

    if (inliers.size() < MIN_INLIERS) {
      fail.add(
          "only %d matching points agree (at least %d needed)"
              .formatted(inliers.size(), MIN_INLIERS));
    }
    if (rms > MAX_RMS_MM) {
      fail.add(String.format(Locale.ROOT, "residual %.2f mm is too large", rms));
    }
    if (Math.abs(fit.degrees()) > MAX_ROTATION_DEG) {
      fail.add(
          String.format(
              Locale.ROOT, "rotation %.1f deg is more than a film can turn", fit.degrees()));
    }
    double spanY =
        inliers.stream().mapToDouble(Rigid.Pair::ty).max().orElse(0)
            - inliers.stream().mapToDouble(Rigid.Pair::ty).min().orElse(0);
    if ((hi - lo) < MIN_SPREAD * width || thirds < 2 || Math.max(hi - lo, spanY) < MIN_SPREAD_MM) {
      fail.add(
          "the matching points cluster in one place of the overlap: they do not fix the rotation;"
              + " pick matching points spread across it");
    }
    if (!(sigma[0] <= MAX_ROTATION_SIGMA_DEG)) {
      fail.add(
          String.format(
              Locale.ROOT,
              "the rotation is uncertain by %.2f deg (at most %.2f allowed)",
              sigma[0],
              MAX_ROTATION_SIGMA_DEG));
    }
    if (heldPairs.size() < MIN_HELD) {
      fail.add(
          "only %d held-back points could be matched (at least %d needed to check the fit)"
              .formatted(heldPairs.size(), MIN_HELD));
    } else if (holdout > MAX_HOLDOUT_MM || quarterHeld > 2 * MAX_HOLDOUT_MM) {
      // the median, and the upper quarter at twice the limit: a region that is systematically
      // wrong (the other layer of a knee) shows in the quarter when the median hides it
      fail.add(
          String.format(
              Locale.ROOT,
              "the %d held-back points disagree with the fit (median %.1f mm, upper quarter %.1f"
                  + " mm, worst %.1f mm; at most %.0f and %.0f mm)",
              heldPairs.size(),
              holdout,
              quarterHeld,
              worstHeld,
              MAX_HOLDOUT_MM,
              2 * MAX_HOLDOUT_MM));
    } else {
      check.add(
          String.format(
              Locale.ROOT,
              "%d held-back points agree with the fit (median %.1f mm, upper quarter %.1f mm,"
                  + " worst %.1f mm)",
              heldPairs.size(),
              holdout,
              quarterHeld,
              worstHeld));
    }
    String halves = halvesDisagree(upper, band, fit, inliers);
    if (halves != null) {
      fail.add(halves);
    }
    if (Math.abs(scale - 1) > SCALE_TOLERANCE) {
      check.add(
          String.format(
              Locale.ROOT,
              "the pieces look %.1f %% different in scale: magnification differs, or the match is"
                  + " wrong; the scale is not applied",
              (scale - 1) * 100));
    }
    check.add("automatic match, not reviewed: inspect the overlap");
    check.add("ruler and skin are not yet excluded from the match automatically");
    boolean failed = !fail.isEmpty();
    if (failed) {
      // An automatic seam with enough agreeing points is left where those points agree, by shift
      // alone: the rough position's shift-only search over a long overlap trades a small turn of
      // the leg for a vertical error along a slanted shaft (real case, 10 Nov 2026: the knee-to-
      // ankle seam left 7 mm above where the matched points, every band of the overlap and the
      // machine's own stitch put it). Still no rotation, and still failed.
      boolean onPoints = method == Seam.Method.AUTOMATIC && inliers.size() >= MIN_INLIERS;
      Rigid placed = onPoints ? meanShift(inliers) : rough;
      check.add(
          0,
          String.format(
              Locale.ROOT,
              "placed at the %s (shift %.1f, %.1f mm, rotation %.2f deg) without the failed fit"
                  + " (%d points, rotation %.2f deg, residual %.2f mm): a match the gates refuse"
                  + " keeps no fitted rotation",
              onPoints
                  ? "average shift of the matched points"
                  : method == Seam.Method.AUTOMATIC
                      ? "rough position refined by shift only"
                      : "position set by hand",
              placed.tx(),
              placed.ty(),
              placed.degrees(),
              inliers.size(),
              fit.degrees(),
              rms));
      fit = placed;
      rms = Double.NaN;
      sigma = new double[] {Double.NaN, Double.NaN};
      scale = Double.NaN;
    }
    List<String> reasons = new ArrayList<>(fail);
    reasons.addAll(check);
    return new Seam(
        fit,
        method,
        failed ? Seam.Status.FAILED : Seam.Status.CHECK,
        rms,
        sigma[0],
        sigma[1],
        scale,
        uniqueness,
        holdout,
        inliers,
        reasons);
  }

  /**
   * On a band that spans both halves of the image: do the matched points of one half, fitted on
   * their own, need a different rotation from the fit of them all? Two legs that moved differently
   * between the exposures (parallax) each keep their own seam, and one rigid fit of both turns that
   * difference into a tilt that passes every other gate: on a phantom with the right leg 3 mm
   * higher in the lower piece the whole width passed at -0.77 deg, and at 4 mm at -1.05 deg, where
   * each leg alone is level (review of 11 Nov 2026; case LL-001 showed the same on its knee seam).
   * A film that truly turned shows the same rotation in each half. A half is asked only when its
   * points fix its rotation as well as a whole seam must ({@link #MAX_ROTATION_SIGMA_DEG}), and it
   * disagrees only beyond {@link #MAX_LEG_DISAGREE_DEG} and three times its own uncertainty, so a
   * single leg that straddles the middle of a wide film is not judged by a handful of points on one
   * side.
   *
   * @return the reason the seam fails, or null
   */
  static String halvesDisagree(MatchImage upper, Band band, Rigid fit, List<Rigid.Pair> inliers) {
    if (band.from() >= 0.5 || band.to() <= 0.5) {
      return null;
    }
    double mid = upper.widthMm() / 2;
    String worst = null;
    double worstDeg = MAX_LEG_DISAGREE_DEG;
    for (boolean leftHalf : new boolean[] {true, false}) {
      List<Rigid.Pair> part = inliers.stream().filter(q -> (q.tx() < mid) == leftHalf).toList();
      if (part.size() < MIN_INLIERS) {
        continue;
      }
      Rigid own = Rigid.fit(part);
      double sigma = Rigid.uncertainty(own, part)[0];
      double deg = Math.abs(own.degrees() - fit.degrees());
      if (sigma <= MAX_ROTATION_SIGMA_DEG && deg > Math.max(worstDeg, 3 * sigma)) {
        worstDeg = deg;
        worst =
            String.format(
                Locale.ROOT,
                "the %d matched points on the %s half of the image, fitted on their own, turn"
                    + " %.2f deg where the fit of all %d points turns %.2f deg: the two sides do"
                    + " not share one seam (two legs that moved differently between the"
                    + " exposures, parallax). Choose one target leg and match on its half of the"
                    + " image",
                part.size(),
                leftHalf ? "left" : "right",
                own.degrees(),
                inliers.size(),
                fit.degrees());
      }
    }
    return worst;
  }

  /**
   * The shift alone that best carries the matched points of the lower piece onto the upper one:
   * their average displacement, which is right at their centre whatever small turn the fit found.
   */
  static Rigid meanShift(List<Rigid.Pair> pairs) {
    double dx = 0;
    double dy = 0;
    for (Rigid.Pair q : pairs) {
      dx += q.tx() - q.fx();
      dy += q.ty() - q.fy();
    }
    return Rigid.shift(dx / pairs.size(), dy / pairs.size());
  }

  /** Two legs' seams may differ by this much at the overlap's centre and still be one seam. */
  static final double MAX_LEG_DISAGREE_MM = 1.5;

  static final double MAX_LEG_DISAGREE_DEG = 0.3;

  /**
   * Target leg "Both" on the whole width: one rigid seam must fit both legs, which parallax or
   * movement between exposures can prevent (Fable review F5, 7 Nov 2026). Each half is matched on
   * its own as well. Two halves that disagree fail the seam; two that agree are said to agree, and
   * when the whole-width match failed only because its points lay on one leg, the half with the
   * smaller uncertainty is used and the other half's agreement is stated.
   */
  static Seam matchBothLegs(MatchImage upper, MatchImage lower) {
    return bothLegs(
        upper,
        lower,
        match(upper, lower, Band.WHOLE),
        match(upper, lower, Band.LEFT_HALF),
        match(upper, lower, Band.RIGHT_HALF),
        ", or line the seam up by hand");
  }

  /**
   * Target leg "Both", refined from a position set by hand (Sami, 10 Nov 2026): {@link #refine} on
   * the whole width and on each half from that position, judged exactly as {@link #matchBothLegs}
   * judges, so a hand-set seam on a film of both legs cannot become "check" on one leg's evidence.
   * A seam this refuses stays where the user put it, without the refused fit.
   */
  static Seam refineBothLegs(MatchImage upper, MatchImage lower, Rigid start) {
    Seam judged =
        bothLegs(
            upper,
            lower,
            refine(upper, lower, Band.WHOLE, start),
            refine(upper, lower, Band.LEFT_HALF, start),
            refine(upper, lower, Band.RIGHT_HALF, start),
            ", or fit matching points picked on both legs");
    if (judged.status() == Seam.Status.FAILED && !judged.transform().equals(start)) {
      return new Seam(
          start,
          judged.method(),
          Seam.Status.FAILED,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          Double.NaN,
          judged.uniqueness(),
          Double.NaN,
          List.of(),
          judged.reasons());
    }
    return judged;
  }

  /**
   * @param otherwise what else the user can do when the legs cannot be confirmed, after "choose one
   *     target leg and match on its half of the image"
   */
  private static Seam bothLegs(
      MatchImage upper, MatchImage lower, Seam whole, Seam left, Seam right, String otherwise) {
    // Both needs both legs matched on their own, agreeing, and the whole-width match passing;
    // a failure of any of these fails the seam (Kimi, second review: a passing whole-width seam
    // must not stand in for a leg that failed, and two halves must not erase a whole-width failure)
    if (left.status() == Seam.Status.FAILED || right.status() == Seam.Status.FAILED) {
      return refused(
          whole,
          "the legs could not be matched one at a time to check that they agree: %s leg failed;"
                  .formatted(left.status() == Seam.Status.FAILED ? "the left" : "the right")
              + " choose one target leg and match on its half of the image"
              + otherwise);
    }
    Rigid base = whole.status() == Seam.Status.CHECK ? whole.transform() : left.transform();
    double[] box = overlap(upper.widthMm(), upper.heightMm(), base, lower);
    double[] q = base.inverse().apply((box[0] + box[2]) / 2, (box[1] + box[3]) / 2);
    double[] a = left.transform().apply(q[0], q[1]);
    double[] b = right.transform().apply(q[0], q[1]);
    double mm = Math.hypot(a[0] - b[0], a[1] - b[1]);
    double deg = Math.abs(left.transform().degrees() - right.transform().degrees());
    String words =
        String.format(
            Locale.ROOT,
            "the left and right legs, matched one at a time, differ by %.1f mm and %.2f deg",
            mm,
            deg);
    if (mm > MAX_LEG_DISAGREE_MM || deg > MAX_LEG_DISAGREE_DEG) {
      return refused(
          whole,
          words
              + ": parallax or movement between the exposures. Choose one target leg and match"
              + " on its half of the image");
    }
    if (whole.status() == Seam.Status.CHECK) {
      return withReason(whole, Seam.Status.CHECK, words + ", within the limits");
    }
    return refused(
        whole,
        words
            + ", within the limits, but the match on the whole width failed; choose one target"
            + " leg and match on its half of the image"
            + otherwise);
  }

  /**
   * Target leg "Both", from the user's point pairs (Sami, 10 Nov 2026): {@link #manual}, and the
   * pairs must lie on both legs, at least one in each half of the upper piece as shown. The
   * left-out check then holds each leg's points against the fit of the others, so pairs picked on
   * one leg alone cannot confirm a seam for both.
   */
  static Seam manualBothLegs(List<Rigid.Pair> pairs, double upperWidthMm) {
    Seam seam = manual(pairs);
    long onLeft = pairs.stream().filter(p -> p.tx() < upperWidthMm / 2).count();
    if (pairs.isEmpty()) {
      return seam;
    }
    if (onLeft == 0 || onLeft == pairs.size()) {
      return withReason(
          seam,
          Seam.Status.FAILED,
          "target leg Both, but every point pair is on the %s half of the image: pick at least"
                  .formatted(onLeft == 0 ? "right" : "left")
              + " one pair on the other leg too, so that both legs are held to one seam; or"
              + " choose one target leg");
    }
    return seam.status() == Seam.Status.CHECK
        ? withReason(seam, Seam.Status.CHECK, "the point pairs lie on both legs")
        : seam;
  }

  /**
   * The whole-width seam refused for both legs. One that had passed on its own still carries its
   * fitted rotation and residual: it is left at the average shift of its matched points instead,
   * with no figures, as {@link #judge} leaves any refused fit (review of 11 Nov 2026: with the legs
   * 3 mm apart the refused seam was saved turned -0.77 deg, the fault stitch 0.4 removed from a
   * single band).
   */
  private static Seam refused(Seam whole, String reason) {
    if (whole.status() == Seam.Status.FAILED) {
      return withReason(whole, Seam.Status.FAILED, reason);
    }
    Rigid placed =
        whole.pairs().isEmpty()
            ? Rigid.shift(whole.transform().tx(), whole.transform().ty())
            : meanShift(whole.pairs());
    List<String> reasons = new ArrayList<>();
    reasons.add(reason);
    reasons.add(
        String.format(
            Locale.ROOT,
            "placed at the average shift of the matched points (shift %.1f, %.1f mm, rotation"
                + " 0.00 deg) without the refused fit (%d points, rotation %.2f deg, residual"
                + " %.2f mm): a match the gates refuse keeps no fitted rotation",
            placed.tx(),
            placed.ty(),
            whole.pairs().size(),
            whole.transform().degrees(),
            whole.rmsMm()));
    reasons.addAll(whole.reasons());
    return new Seam(
        placed,
        whole.method(),
        Seam.Status.FAILED,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        whole.uniqueness(),
        whole.holdoutMm(),
        whole.pairs(),
        reasons);
  }

  private static Seam withReason(Seam s, Seam.Status status, String reason) {
    List<String> reasons = new ArrayList<>();
    reasons.add(reason);
    reasons.addAll(s.reasons());
    return new Seam(
        s.transform(),
        s.method(),
        status,
        s.rmsMm(),
        s.rotationSigmaDeg(),
        s.shiftSigmaMm(),
        s.scale(),
        s.uniqueness(),
        s.holdoutMm(),
        s.pairs(),
        reasons);
  }

  /**
   * A seam from the user's point pairs (design section 5.4). Two pairs fit perfectly whether right
   * or wrong, so with three or more each pair is checked against the fit of the others; a pair that
   * disagrees by more than 2 mm fails the seam.
   */
  static Seam manual(List<Rigid.Pair> pairs) {
    if (pairs.isEmpty()) {
      return Seam.failed(Rigid.IDENTITY, Seam.Method.MANUAL, List.of("no point pairs picked"));
    }
    Rigid fit = Rigid.fit(pairs);
    List<String> reasons = new ArrayList<>();
    Seam.Status status = Seam.Status.CHECK;
    double spread =
        pairs.stream().mapToDouble(Rigid.Pair::tx).max().orElse(0)
            - pairs.stream().mapToDouble(Rigid.Pair::tx).min().orElse(0);
    // two pairs fit exactly whatever they are, and clustered pairs do not fix the rotation: such a
    // seam is unconfirmed and stays failed, it cannot be saved (Kimi, second review)
    if (pairs.size() < 3) {
      status = Seam.Status.FAILED;
      reasons.add(
          "manual seam with %d point pairs, unconfirmed: three or more agreeing pairs on bone,"
                  .formatted(pairs.size())
              + " spread across the overlap, are needed for a left-out check");
    } else if (spread < MIN_MANUAL_SPREAD_MM) {
      status = Seam.Status.FAILED;
      reasons.add(
          String.format(
              Locale.ROOT,
              "the point pairs lie within %.0f mm of each other: spread them at least %.0f mm"
                  + " across the overlap so they fix the rotation",
              spread,
              MIN_MANUAL_SPREAD_MM));
    } else {
      double worst = 0;
      for (int i = 0; i < pairs.size(); i++) {
        List<Rigid.Pair> others = new ArrayList<>(pairs);
        Rigid.Pair left = others.remove(i);
        worst = Math.max(worst, Rigid.fit(others).residual(left));
      }
      if (worst > MAX_HOLDOUT_MM) {
        status = Seam.Status.FAILED;
        reasons.add(
            String.format(
                Locale.ROOT,
                "a point pair disagrees with the fit of the others by %.1f mm: check the points",
                worst));
      } else {
        reasons.add(
            String.format(
                Locale.ROOT,
                "each point pair agrees with the fit of the others within %.1f mm",
                worst));
      }
    }
    if (Math.abs(fit.degrees()) > MAX_ROTATION_DEG) {
      status = Seam.Status.FAILED;
      reasons.add(
          String.format(
              Locale.ROOT, "rotation %.1f deg is more than a film can turn", fit.degrees()));
    }
    reasons.add("manual seam, not reviewed: inspect the overlap");
    double[] sigma = Rigid.uncertainty(fit, pairs);
    return new Seam(
        fit,
        Seam.Method.MANUAL,
        status,
        pairs.size() >= 2 ? fit.rms(pairs) : Double.NaN,
        sigma[0],
        sigma[1],
        pairs.size() >= 2 ? Rigid.similarityScale(pairs) : Double.NaN,
        Double.NaN,
        Double.NaN,
        pairs,
        reasons);
  }

  /** The fine match from one rough start: patch pairs, RANSAC inliers, the fit, held-back pairs. */
  private record Fine(
      Rigid start,
      List<Rigid.Pair> pairs,
      List<Rigid.Pair> heldPairs,
      List<Rigid.Pair> inliers,
      Rigid fit) {
    double rms() {
      return inliers.size() < 2 ? Double.POSITIVE_INFINITY : fit.rms(inliers);
    }
  }

  /**
   * Patches matched from {@code start}, a RANSAC fit, then every patch matched again from that fit
   * so rotation is followed to the ends of the overlap (Kimi review), and kept if no worse. Held
   * patches are matched from the final fit and never enter it.
   */
  private static Fine fine(MatchImage upper, MatchImage lower, Band band, Rigid start) {
    List<Patch> patches = patches(upper, lower, start, band, new double[2]);
    List<Patch> held = holdOut(patches);
    List<Rigid.Pair> pairs = matchAll(patches, upper, lower, start);
    if (pairs.size() < 2) {
      return new Fine(start, pairs, matchAll(held, upper, lower, start), List.of(), start);
    }
    List<Rigid.Pair> inliers = ransac(pairs);
    Rigid fit = Rigid.fit(inliers);
    List<Rigid.Pair> again = matchAll(patches, upper, lower, fit);
    if (again.size() >= 2) {
      List<Rigid.Pair> in2 = ransac(again);
      if (in2.size() >= inliers.size()) {
        pairs = again;
        inliers = in2;
        fit = Rigid.fit(in2);
      }
    }
    return new Fine(start, pairs, matchAll(held, upper, lower, fit), inliers, fit);
  }

  /** How far apart two seams put the centre of the overlap, in mm of the upper piece. */
  private static double differs(Rigid a, Rigid b, MatchImage upper, MatchImage lower) {
    double[] box = overlap(upper.widthMm(), upper.heightMm(), a, lower);
    double[] q = a.inverse().apply((box[0] + box[2]) / 2, (box[1] + box[3]) / 2);
    double[] pa = a.apply(q[0], q[1]);
    double[] pb = b.apply(q[0], q[1]);
    return Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
  }

  /** Result of the coarse search: the lower piece's offset in the upper frame, in mm. */
  record Coarse(
      double dx, double dy, double score, double uniqueness, double slideXMm, double slideYMm) {}

  /** The best rough position, or null when the pieces cannot overlap. */
  static Coarse coarse(MatchImage upper, MatchImage lower, Band band) {
    List<Coarse> peaks = coarsePeaks(upper, lower, band, 1);
    return peaks.isEmpty() ? null : peaks.get(0);
  }

  /**
   * The best rough position (with its uniqueness and slide), then up to {@code k - 1} further
   * positive local peaks, each more than 8 mm from every higher one. Empty when the pieces cannot
   * overlap.
   */
  static List<Coarse> coarsePeaks(MatchImage upper, MatchImage lower, Band band, int k) {
    return coarsePeaks(upper, lower, band, k, null, 0);
  }

  /** As above, limited to positions within {@code radiusMm} of {@code near} when it is given. */
  static List<Coarse> coarsePeaks(
      MatchImage upper, MatchImage lower, Band band, int k, Rigid near, double radiusMm) {
    List<Coarse> out = new ArrayList<>();
    Gray u = upper.coarseGrad();
    Gray l = lower.coarseGrad();
    double g = MatchImage.COARSE_MM;
    double maxOverlap = MAX_OVERLAP_FRACTION * Math.min(upper.heightMm(), lower.heightMm());
    int oyMin = (int) Math.ceil((u.heightMm() - maxOverlap) / g);
    int oyMax = (int) Math.floor((u.heightMm() - MIN_OVERLAP_MM) / g);
    int oxMax = (int) Math.round(MAX_SIDE_SHIFT_MM / g);
    if (oyMax < oyMin) {
      return out;
    }
    int nx = 2 * oxMax + 1;
    int ny = oyMax - oyMin + 1;
    double[] map = new double[nx * ny];
    Arrays.fill(map, Double.NEGATIVE_INFINITY);
    for (int j = 0; j < ny; j++) {
      for (int i = 0; i < nx; i++) {
        if (near != null
            && (Math.abs((i - oxMax) * g - near.tx()) > radiusMm
                || Math.abs((j + oyMin) * g - near.ty()) > radiusMm)) {
          continue;
        }
        map[j * nx + i] = ncc(u, l, i - oxMax, j + oyMin, band);
      }
    }
    int best = argmax(map);
    if (best < 0 || map[best] == Double.NEGATIVE_INFINITY) {
      return out;
    }
    int bx = best % nx;
    int by = best / nx;
    int r = 4; // 8 mm: outside the best peak's basin
    double second = Double.NEGATIVE_INFINITY;
    for (int j = 0; j < ny; j++) {
      for (int i = 0; i < nx; i++) {
        if (Math.max(Math.abs(i - bx), Math.abs(j - by)) > r) {
          second = Math.max(second, map[j * nx + i]);
        }
      }
    }
    double score = map[best];
    double uniqueness =
        score <= 0 ? 0 : second <= 0 ? Double.POSITIVE_INFINITY : score / Math.max(second, 1e-6);
    double slideX = slide(map, nx, ny, bx, by, 1, 0, score) * g;
    double slideY = slide(map, nx, ny, bx, by, 0, 1, score) * g;
    out.add(new Coarse((bx - oxMax) * g, (by + oyMin) * g, score, uniqueness, slideX, slideY));

    List<int[]> taken = new ArrayList<>();
    taken.add(new int[] {bx, by});
    while (out.size() < k) {
      int pick = -1;
      for (int j = 0; j < ny; j++) {
        for (int i = 0; i < nx; i++) {
          double v = map[j * nx + i];
          if (v <= 0 || (pick >= 0 && v <= map[pick]) || near(taken, i, j, r)) {
            continue;
          }
          pick = j * nx + i;
        }
      }
      if (pick < 0) {
        break;
      }
      int i = pick % nx;
      int j = pick / nx;
      taken.add(new int[] {i, j});
      out.add(
          new Coarse(
              (i - oxMax) * g, (j + oyMin) * g, map[pick], Double.NaN, Double.NaN, Double.NaN));
    }
    return out;
  }

  private static boolean near(List<int[]> taken, int i, int j, int r) {
    for (int[] t : taken) {
      if (Math.max(Math.abs(t[0] - i), Math.abs(t[1] - j)) <= r) {
        return true;
      }
    }
    return false;
  }

  /** Cells from the peak, either way along one axis, that score within SLIDE_SCORE of it. */
  private static int slide(
      double[] map, int nx, int ny, int bx, int by, int sx, int sy, double top) {
    int far = 0;
    for (int dir = -1; dir <= 1; dir += 2) {
      for (int k = 1; ; k++) {
        int i = bx + dir * k * sx;
        int j = by + dir * k * sy;
        if (i < 0 || j < 0 || i >= nx || j >= ny || map[j * nx + i] < top - SLIDE_SCORE) {
          far = Math.max(far, k - 1);
          break;
        }
      }
    }
    return far;
  }

  /** Correlation of the overlap when lower pixel (x, y) sits on upper pixel (x + ox, y + oy). */
  private static double ncc(Gray u, Gray l, int ox, int oy, Band band) {
    int x0 = Math.max(Math.max(0, -ox), (int) Math.ceil(band.from() * u.width()) - ox);
    int x1 =
        Math.min(Math.min(l.width(), u.width() - ox), (int) Math.floor(band.to() * u.width()) - ox);
    int y0 = Math.max(0, -oy);
    int y1 = Math.min(l.height(), u.height() - oy);
    double sa = 0;
    double sb = 0;
    double saa = 0;
    double sbb = 0;
    double sab = 0;
    int n = 0;
    for (int y = y0; y < y1; y++) {
      int lu = (y + oy) * u.width() + ox;
      int ll = y * l.width();
      for (int x = x0; x < x1; x++) {
        float a = u.data()[lu + x];
        float b = l.data()[ll + x];
        if (Float.isNaN(a) || Float.isNaN(b)) {
          continue;
        }
        sa += a;
        sb += b;
        saa += a * a;
        sbb += b * b;
        sab += a * b;
        n++;
      }
    }
    if (n < MIN_COARSE_CELLS) {
      return Double.NEGATIVE_INFINITY;
    }
    double cov = sab - sa * sb / n;
    double va = saa - sa * sa / n;
    double vb = sbb - sb * sb / n;
    return va <= 1e-12 || vb <= 1e-12 ? Double.NEGATIVE_INFINITY : cov / Math.sqrt(va * vb);
  }

  /**
   * Smaller over larger eigenvalue of the structure tensor (0: one edge direction), over the bright
   * part of the overlap inside the band, so the background's edges cannot supply the second
   * direction (Astra, code review P1).
   */
  static double edgeRatio(Gray upperCoarse, Rigid lowerToUpper, MatchImage lower, Band band) {
    double[] box = bandBox(upperCoarse, lowerToUpper, lower, band);
    Gray[] g = upperCoarse.gradients();
    double mm0 = upperCoarse.mm();
    double bright =
        otsu(
            upperCoarse,
            (int) (box[0] / mm0),
            (int) (box[1] / mm0),
            (int) Math.ceil(box[2] / mm0),
            (int) Math.ceil(box[3] / mm0));
    double sxx = 0;
    double syy = 0;
    double sxy = 0;
    double mm = upperCoarse.mm();
    for (int y = 0; y < upperCoarse.height(); y++) {
      double cy = (y + 0.5) * mm;
      if (cy < box[1] || cy > box[3]) {
        continue;
      }
      for (int x = 0; x < upperCoarse.width(); x++) {
        double cx = (x + 0.5) * mm;
        float gx = g[0].at(x, y);
        float gy = g[1].at(x, y);
        float v = upperCoarse.at(x, y);
        if (cx < box[0] || cx > box[2] || Float.isNaN(gx) || Float.isNaN(gy) || !(v >= bright)) {
          continue;
        }
        sxx += gx * gx;
        syy += gy * gy;
        sxy += gx * gy;
      }
    }
    double[] ev = eigen(sxx, sxy, syy);
    return ev[0] <= 1e-12 ? 0 : ev[1] / ev[0];
  }

  /** The overlap rectangle in the upper frame {x0, y0, x1, y1} for a shift-dominated seam. */
  static double[] overlap(double upperW, double upperH, Rigid lowerToUpper, MatchImage lower) {
    double[] tl = lowerToUpper.apply(0, 0);
    double[] br = lowerToUpper.apply(lower.widthMm(), lower.heightMm());
    return new double[] {
      Math.max(0, Math.min(tl[0], br[0])),
      Math.max(0, Math.min(tl[1], br[1])),
      Math.min(upperW, Math.max(tl[0], br[0])),
      Math.min(upperH, Math.max(tl[1], br[1]))
    };
  }

  /** The overlap rectangle limited to the band's columns of the upper piece. */
  static double[] bandBox(Gray upperGrid, Rigid lowerToUpper, MatchImage lower, Band band) {
    double[] box = overlap(upperGrid.widthMm(), upperGrid.heightMm(), lowerToUpper, lower);
    box[0] = Math.max(box[0], band.from() * upperGrid.widthMm());
    box[2] = Math.min(box[2], band.to() * upperGrid.widthMm());
    return box;
  }

  /** A textured patch of the upper piece, by its top-left pixel on the fine grid. */
  record Patch(int x, int y, double strength, int region) {}

  /**
   * The textured patches of the overlap, strongest first. {@code extent} receives the side-to-side
   * span of all candidate patch centres in mm, and regions are tertiles of that span by position,
   * so a single leg still has three regions to hold a patch back from.
   */
  private static List<Patch> patches(
      MatchImage upper, MatchImage lower, Rigid start, Band band, double[] extent) {
    extent[0] = Double.NaN;
    extent[1] = Double.NaN;
    Gray f = upper.fine();
    double[] box = bandBox(f, start, lower, band);
    double margin = 3;
    int x0 = (int) Math.ceil((box[0] + margin) / f.mm());
    int x1 = (int) Math.floor((box[2] - margin) / f.mm()) - PATCH;
    int y0 = (int) Math.ceil((box[1] + margin) / f.mm());
    int y1 = (int) Math.floor((box[3] - margin) / f.mm()) - PATCH;
    if (x1 < x0 || y1 < y0) {
      return new ArrayList<>();
    }
    double bright = otsu(f, x0, y0, x1 + PATCH, y1 + PATCH);
    Gray[] g = f.gradients();
    List<Patch> out = new ArrayList<>();
    for (int y = y0; y <= y1; y += STEP) {
      for (int x = x0; x <= x1; x += STEP) {
        double[] st = patchStats(f, g, x, y);
        if (st == null || st[0] < bright) {
          continue;
        }
        double[] ev = eigen(st[1], st[2], st[3]);
        if (ev[0] <= 1e-12 || ev[1] / ev[0] < MIN_PATCH_ISOTROPY) {
          continue;
        }
        out.add(new Patch(x, y, ev[1], 0));
      }
    }
    if (out.isEmpty()) {
      return out;
    }
    int[] xs = out.stream().mapToInt(Patch::x).sorted().toArray();
    int cut1 = xs[xs.length / REGIONS];
    int cut2 = xs[Math.min(xs.length - 1, 2 * xs.length / REGIONS)];
    extent[0] = (xs[0] + PATCH / 2.0) * f.mm();
    extent[1] = (xs[xs.length - 1] + PATCH / 2.0) * f.mm();
    out.replaceAll(p -> new Patch(p.x, p.y, p.strength, p.x < cut1 ? 0 : p.x < cut2 ? 1 : 2));
    out.sort(Comparator.comparingDouble(Patch::strength).reversed());
    return new ArrayList<>(out.subList(0, Math.min(MAX_PATCHES, out.size())));
  }

  /** {mean, sxx, sxy, syy} of a patch, or null when more than a tenth of it is masked. */
  private static double[] patchStats(Gray f, Gray[] g, int px, int py) {
    double mean = 0;
    double sxx = 0;
    double sxy = 0;
    double syy = 0;
    int n = 0;
    for (int y = py; y < py + PATCH; y++) {
      for (int x = px; x < px + PATCH; x++) {
        float v = f.at(x, y);
        float gx = g[0].at(x, y);
        float gy = g[1].at(x, y);
        if (Float.isNaN(v) || Float.isNaN(gx) || Float.isNaN(gy)) {
          continue;
        }
        mean += v;
        sxx += gx * gx;
        sxy += gx * gy;
        syy += gy * gy;
        n++;
      }
    }
    return n < PATCH * PATCH * 0.9 ? null : new double[] {mean / n, sxx, sxy, syy};
  }

  // every tenth patch by strength is kept out of the fit and checks it, so the held patches lie
  // wherever the evidence lies; a fitted patch within half a patch of a held one leaves the fit.
  // A fitted patch two steps away (8 mm) may still share up to half its width with a held one,
  // so the check is largely, not wholly, independent of the fitted pixels (Kimi, second review).
  // Measured on the 7 Nov 2026 case and the phantoms, a wider exclusion costs too much: removing
  // those neighbours too took the 343 mm seam from 59 to 24 pairs and the single-leg phantom's
  // position error from 0.3 to 0.57 mm; excluding every patch that shares a pixel starved the fit
  // (8 pairs) and failed the two-leg phantoms; a few strongest-first held patches on a narrow leg
  // took the fit's evidence, and on the knee were too few to refuse the wrong layer. Earlier
  // still: one patch per region, of which on a real film usually one matched.
  private static List<Patch> holdOut(List<Patch> patches) {
    List<Patch> held = new ArrayList<>();
    for (int i = HOLD_EVERY / 2; i < patches.size(); i += HOLD_EVERY) {
      held.add(patches.get(i));
    }
    patches.removeIf(
        p ->
            held.stream()
                .anyMatch(
                    h ->
                        Math.abs(h.x - p.x) < HELD_EXCLUDE * PATCH
                            && Math.abs(h.y - p.y) < HELD_EXCLUDE * PATCH));
    return held;
  }

  private static List<Rigid.Pair> matchAll(
      List<Patch> patches, MatchImage upper, MatchImage lower, Rigid start) {
    Rigid toLower = start.inverse();
    List<Rigid.Pair> pairs = new ArrayList<>();
    for (Patch p : patches) {
      Rigid.Pair pair = matchPatch(p, upper.fine(), lower.fine(), toLower);
      if (pair != null) {
        pairs.add(pair);
      }
    }
    return pairs;
  }

  private static Rigid.Pair matchPatch(Patch p, Gray u, Gray l, Rigid toLower) {
    double mm = u.mm();
    double cux = (p.x + PATCH / 2.0) * mm;
    double cuy = (p.y + PATCH / 2.0) * mm;
    double[] guess = toLower.apply(cux, cuy);
    int gx = (int) Math.round(guess[0] / mm - PATCH / 2.0);
    int gy = (int) Math.round(guess[1] / mm - PATCH / 2.0);
    int n = 2 * SEARCH + 1;
    double[] score = new double[n * n];
    for (int j = 0; j < n; j++) {
      for (int i = 0; i < n; i++) {
        score[j * n + i] = patchNcc(u, p.x, p.y, l, gx + i - SEARCH, gy + j - SEARCH);
      }
    }
    int best = argmax(score);
    if (best < 0) {
      return null;
    }
    int bi = best % n;
    int bj = best / n;
    if (score[best] < MIN_PATCH_NCC || bi == 0 || bj == 0 || bi == n - 1 || bj == n - 1) {
      return null; // weak, or the peak lies beyond the search window
    }
    if (!(score[best - 1] - 2 * score[best] + score[best + 1] < 0)
        || !(score[best - n] - 2 * score[best] + score[best + n] < 0)) {
      return null; // flat along one axis: the patch could slide
    }
    for (int j = 0; j < n; j++) {
      for (int i = 0; i < n; i++) {
        if (Math.max(Math.abs(i - bi), Math.abs(j - bj)) > RIDGE_PX
            && score[j * n + i] >= score[best] - RIDGE_SCORE) {
          return null; // as good a match elsewhere: a ridge or a repeat, not a point
        }
      }
    }
    double sx = bi + parabola(score[best - 1], score[best], score[best + 1]);
    double sy = bj + parabola(score[best - n], score[best], score[best + n]);
    double lx = (gx + sx - SEARCH + PATCH / 2.0) * mm;
    double ly = (gy + sy - SEARCH + PATCH / 2.0) * mm;
    return new Rigid.Pair(lx, ly, cux, cuy);
  }

  private static double patchNcc(Gray u, int ux, int uy, Gray l, int lx, int ly) {
    if (lx < 0 || ly < 0 || lx + PATCH > l.width() || ly + PATCH > l.height()) {
      return Double.NEGATIVE_INFINITY;
    }
    double sa = 0;
    double sb = 0;
    double saa = 0;
    double sbb = 0;
    double sab = 0;
    int n = 0;
    for (int y = 0; y < PATCH; y++) {
      int iu = (uy + y) * u.width() + ux;
      int il = (ly + y) * l.width() + lx;
      for (int x = 0; x < PATCH; x++) {
        float a = u.data()[iu + x];
        float b = l.data()[il + x];
        if (Float.isNaN(a) || Float.isNaN(b)) {
          continue;
        }
        sa += a;
        sb += b;
        saa += a * a;
        sbb += b * b;
        sab += a * b;
        n++;
      }
    }
    if (n < PATCH * PATCH * 0.8) {
      return Double.NEGATIVE_INFINITY;
    }
    double va = saa - sa * sa / n;
    double vb = sbb - sb * sb / n;
    return va <= 1e-12 || vb <= 1e-12
        ? Double.NEGATIVE_INFINITY
        : (sab - sa * sb / n) / Math.sqrt(va * vb);
  }

  private static List<Rigid.Pair> ransac(List<Rigid.Pair> pairs) {
    Random rnd = new Random(42); // the same input gives the same seam
    List<Rigid.Pair> best = List.of(pairs.get(0));
    double bestRms = Double.POSITIVE_INFINITY;
    int iterations = Math.min(500, pairs.size() * (pairs.size() - 1));
    for (int it = 0; it < iterations; it++) {
      int a = rnd.nextInt(pairs.size());
      int b = rnd.nextInt(pairs.size() - 1);
      if (b >= a) {
        b++;
      }
      Rigid r = Rigid.fit(List.of(pairs.get(a), pairs.get(b)));
      if (Math.abs(r.degrees()) > MAX_ROTATION_DEG * 2) {
        continue;
      }
      List<Rigid.Pair> in = pairs.stream().filter(p -> r.residual(p) <= INLIER_MM).toList();
      double rms = in.size() < 2 ? Double.POSITIVE_INFINITY : Rigid.fit(in).rms(in);
      if (in.size() > best.size() || (in.size() == best.size() && rms < bestRms)) {
        best = in;
        bestRms = rms;
      }
    }
    Rigid refit = Rigid.fit(best);
    List<Rigid.Pair> again = pairs.stream().filter(p -> refit.residual(p) <= INLIER_MM).toList();
    return again.size() >= best.size() ? again : best;
  }

  static double otsu(Gray f, int x0, int y0, int x1, int y1) {
    int[] hist = new int[64];
    int n = 0;
    for (int y = y0; y < Math.min(y1, f.height()); y++) {
      for (int x = x0; x < Math.min(x1, f.width()); x++) {
        float v = f.at(x, y);
        if (!Float.isNaN(v)) {
          hist[Math.min(63, (int) (v * 64))]++;
          n++;
        }
      }
    }
    if (n == 0) {
      return 0;
    }
    double total = 0;
    for (int i = 0; i < 64; i++) {
      total += i * (double) hist[i];
    }
    double sumB = 0;
    int wB = 0;
    double bestVar = -1;
    int bestT = 0;
    for (int t = 0; t < 64; t++) {
      wB += hist[t];
      if (wB == 0 || wB == n) {
        continue;
      }
      sumB += t * (double) hist[t];
      double mB = sumB / wB;
      double mF = (total - sumB) / (n - wB);
      double between = (double) wB * (n - wB) * (mB - mF) * (mB - mF);
      if (between > bestVar) {
        bestVar = between;
        bestT = t;
      }
    }
    return (bestT + 1) / 64.0;
  }

  /** Eigenvalues {larger, smaller} of [[a, b], [b, c]]. */
  private static double[] eigen(double a, double b, double c) {
    double tr = a + c;
    double d = Math.sqrt(Math.max(0, (a - c) * (a - c) / 4 + b * b));
    return new double[] {tr / 2 + d, Math.max(0, tr / 2 - d)};
  }

  private static double parabola(double l, double c, double r) {
    if (!Double.isFinite(l) || !Double.isFinite(r)) {
      return 0;
    }
    double den = l - 2 * c + r;
    return den >= 0 ? 0 : Math.max(-0.5, Math.min(0.5, (l - r) / (2 * den)));
  }

  private static int argmax(double[] v) {
    int best = -1;
    for (int i = 0; i < v.length; i++) {
      if (!Double.isNaN(v[i]) && (best < 0 || v[i] > v[best])) {
        best = i;
      }
    }
    return best;
  }
}
