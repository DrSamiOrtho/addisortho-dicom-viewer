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
 * correlation search on gradient magnitude, then textured patches matched at 0.5 mm and a RANSAC
 * rigid fit, then the gates. Every seam it returns is "check" or "failed"; none is "good" until the
 * thresholds are measured on real cases (design section 10).
 */
final class SeamMatcher {

  static final double MIN_OVERLAP_MM = 15;
  static final double MAX_OVERLAP_MM = 150;
  static final double MAX_SIDE_SHIFT_MM = 40;
  static final double MAX_ROTATION_DEG = 3;

  // gates (provisional, to be tuned on the 30-case study)
  static final double MIN_UNIQUENESS = 1.15;
  static final double MIN_EDGE_RATIO = 0.08;
  static final int MIN_INLIERS = 6;
  static final double MAX_RMS_MM = 1.5;
  static final double MAX_HOLDOUT_MM = 2.0;
  static final double SCALE_TOLERANCE = 0.01;
  static final double MAX_ROTATION_SIGMA_DEG = 0.15;
  static final double MIN_SPREAD = 0.4;
  static final int MIN_HELD = 2;
  static final double MIN_MANUAL_SPREAD_MM = 30;

  private static final int PATCH = 30; // 15 mm at 0.5 mm
  private static final int STEP = 16;
  private static final int SEARCH = 12; // +/- 6 mm
  private static final int MAX_PATCHES = 80;
  private static final double MIN_PATCH_NCC = 0.6;
  private static final double MIN_PATCH_ISOTROPY = 0.15;
  private static final double INLIER_MM = 1.0;
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
    Coarse coarse = coarse(upper, lower, band);
    if (coarse == null) {
      return Seam.failed(
          Rigid.IDENTITY,
          Seam.Method.AUTOMATIC,
          List.of("no overlap with enough structure was found between these two pieces"));
    }
    Rigid start = Rigid.shift(coarse.dx, coarse.dy);
    List<String> fail = new ArrayList<>();
    List<String> check = new ArrayList<>();
    if (coarse.uniqueness < MIN_UNIQUENESS) {
      fail.add(
          String.format(
              Locale.ROOT,
              "the overlap matches almost as well at another position (uniqueness %.2f):"
                  + " a repeating pattern such as ruler ticks, or too little structure",
              coarse.uniqueness));
    }
    double edgeRatio = edgeRatio(upper.coarse(), start, lower, band);
    if (edgeRatio < MIN_EDGE_RATIO) {
      fail.add(
          "the overlap has edges in one direction only (straight shaft or ruler): the shift along"
              + " the bone is not determined. Pick matching points on something that is not a"
              + " straight line: a ruler numeral, a nutrient foramen, the other leg, a skin crease");
    }

    List<Patch> patches = patches(upper, lower, start, band);
    List<Patch> held = holdOut(patches);
    List<Rigid.Pair> pairs = matchAll(patches, upper, lower, start);
    List<Rigid.Pair> heldPairs = matchAll(held, upper, lower, start);
    if (pairs.size() < 2) {
      fail.add("too few matching points on structure in the overlap");
      Seam f = Seam.failed(start, Seam.Method.AUTOMATIC, fail);
      return new Seam(
          start,
          f.method(),
          f.status(),
          f.rmsMm(),
          f.rotationSigmaDeg(),
          f.shiftSigmaMm(),
          f.scale(),
          coarse.uniqueness,
          f.holdoutMm(),
          List.of(),
          fail);
    }
    List<Rigid.Pair> inliers = ransac(pairs);
    Rigid fit = Rigid.fit(inliers);
    double rms = fit.rms(inliers);
    double[] sigma = Rigid.uncertainty(fit, inliers);
    double scale = Rigid.similarityScale(inliers);
    double holdout = heldPairs.stream().mapToDouble(fit::residual).max().orElse(Double.NaN);
    double[] box = bandBox(upper.fine(), start, lower, band);
    double width = Math.max(1e-6, box[2] - box[0]);
    double lo = inliers.stream().mapToDouble(Rigid.Pair::tx).min().orElse(0);
    double hi = inliers.stream().mapToDouble(Rigid.Pair::tx).max().orElse(0);
    long thirds =
        inliers.stream()
            .mapToInt(q -> (int) Math.min(2, Math.max(0, (q.tx() - box[0]) * 3 / width)))
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
    if ((hi - lo) < MIN_SPREAD * width || thirds < 2) {
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
    } else if (holdout > MAX_HOLDOUT_MM) {
      fail.add(
          String.format(
              Locale.ROOT, "a held-back point disagrees with the fit by %.1f mm", holdout));
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
    List<String> reasons = new ArrayList<>(fail);
    reasons.addAll(check);
    return new Seam(
        fit,
        Seam.Method.AUTOMATIC,
        failed ? Seam.Status.FAILED : Seam.Status.CHECK,
        rms,
        sigma[0],
        sigma[1],
        scale,
        coarse.uniqueness,
        holdout,
        inliers,
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
    if (pairs.size() < 3) {
      reasons.add(
          "manual seam with %d point pairs: three or more agreeing pairs on bone, spread across the"
                  .formatted(pairs.size())
              + " overlap, are needed for a left-out check");
    } else if (spread < MIN_MANUAL_SPREAD_MM) {
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

  /** Result of the coarse search: the lower piece's offset in the upper frame, in mm. */
  record Coarse(double dx, double dy, double score, double uniqueness) {}

  static Coarse coarse(MatchImage upper, MatchImage lower, Band band) {
    Gray u = upper.coarseGrad();
    Gray l = lower.coarseGrad();
    double g = MatchImage.COARSE_MM;
    double maxOverlap =
        Math.min(MAX_OVERLAP_MM, 0.6 * Math.min(upper.heightMm(), lower.heightMm()));
    int oyMin = (int) Math.ceil((u.heightMm() - maxOverlap) / g);
    int oyMax = (int) Math.floor((u.heightMm() - MIN_OVERLAP_MM) / g);
    int oxMax = (int) Math.round(MAX_SIDE_SHIFT_MM / g);
    if (oyMax < oyMin) {
      return null;
    }
    int nx = 2 * oxMax + 1;
    int ny = oyMax - oyMin + 1;
    double[] map = new double[nx * ny];
    Arrays.fill(map, Double.NEGATIVE_INFINITY);
    for (int j = 0; j < ny; j++) {
      for (int i = 0; i < nx; i++) {
        map[j * nx + i] = ncc(u, l, i - oxMax, j + oyMin, band);
      }
    }
    int best = argmax(map);
    if (best < 0 || map[best] == Double.NEGATIVE_INFINITY) {
      return null;
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
    return new Coarse((bx - oxMax) * g, (by + oyMin) * g, score, uniqueness);
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

  private static List<Patch> patches(MatchImage upper, MatchImage lower, Rigid start, Band band) {
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
        int region = Math.min(REGIONS - 1, (x - x0) * REGIONS / Math.max(1, x1 - x0 + 1));
        out.add(new Patch(x, y, ev[1], region));
      }
    }
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

  // one patch per region (the median-strength one) is kept out of the fit and checks it; patches
  // that overlap it leave the fit too, so the check is independent of the fitted pixels
  private static List<Patch> holdOut(List<Patch> patches) {
    List<Patch> held = new ArrayList<>();
    for (int r = 0; r < REGIONS; r++) {
      int region = r;
      List<Patch> in = patches.stream().filter(p -> p.region == region).toList();
      if (in.size() >= 4) {
        held.add(in.get(in.size() / 2));
      }
    }
    patches.removeIf(
        p ->
            held.stream()
                .anyMatch(h -> Math.abs(h.x - p.x) < PATCH && Math.abs(h.y - p.y) < PATCH));
    return held;
  }

  private static List<Rigid.Pair> matchAll(
      List<Patch> patches, MatchImage upper, MatchImage lower, Rigid start) {
    Rigid toLower = start.inverse();
    List<Rigid.Pair> pairs = new ArrayList<>();
    for (Patch p : patches) {
      Rigid.Pair pair = matchPatch(p, upper.fineGrad(), lower.fineGrad(), toLower);
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

  private static double otsu(Gray f, int x0, int y0, int x1, int y1) {
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
