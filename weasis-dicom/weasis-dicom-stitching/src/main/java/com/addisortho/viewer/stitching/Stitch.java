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
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The pieces in order head to foot, the seams between them and the user's confirmations: from
 * these, where each piece lands on the output grid, the grey mapping that joins their exposures,
 * and the words the output must carry (design sections 6 and 7).
 */
final class Stitch {

  /** What the user confirmed before the match (design section 4, step 2). */
  record Choices(String projection, String position, Leg leg, SeamMatcher.Band band) {}

  /** The target leg. There is no default. */
  enum Leg {
    RIGHT("Right", "R"),
    LEFT("Left", "L"),
    BOTH("Both", "B");

    final String word;
    final String code;

    Leg(String word, String code) {
      this.word = word;
      this.code = code;
    }

    @Override
    public String toString() {
      return word;
    }
  }

  static final double MIN_OUTPUT_MM = 0.15;
  static final long MAX_PIXELS = 32_000_000L;
  static final double FEATHER_MM = 10;

  final List<Piece> pieces;
  final List<Seam> seams;
  final Choices choices;
  // the cut row of each seam in its upper piece's mm, set by hand; NaN: found automatically
  private final double[] cuts;
  private final Cut.Row[] rows;

  /**
   * Saved although a seam failed (Sami, 7 Nov 2026): the picture is kept for viewing, with the
   * warning burned in, without scale so nothing can be measured on it, and refused by the Template
   * button (Kimi, second review: an unconfirmed seam must not produce a measurable image).
   */
  final boolean unverified;

  Stitch(List<Piece> pieces, List<Seam> seams, Choices choices) {
    this(pieces, seams, choices, null, false);
  }

  Stitch(List<Piece> pieces, List<Seam> seams, Choices choices, double[] cuts) {
    this(pieces, seams, choices, cuts, false);
  }

  /**
   * @param cuts the cut row of each seam in its upper piece's millimetres, where the user dragged
   *     it; NaN (or null) for a cut found automatically (never through a sphere, see {@link Cut})
   */
  Stitch(List<Piece> pieces, List<Seam> seams, Choices choices, double[] cuts, boolean unverified) {
    if (seams.size() != pieces.size() - 1) {
      throw new IllegalArgumentException("one seam between each two pieces");
    }
    this.pieces = List.copyOf(pieces);
    this.seams = List.copyOf(seams);
    this.choices = choices;
    this.unverified = unverified;
    this.cuts = new double[seams.size()];
    Arrays.fill(this.cuts, Double.NaN);
    if (cuts != null) {
      System.arraycopy(cuts, 0, this.cuts, 0, Math.min(cuts.length, this.cuts.length));
    }
    this.rows = new Cut.Row[seams.size()];
  }

  /** Where the user put the cut of seam i, in its upper piece's mm; NaN when it is automatic. */
  double requestedCut(int i) {
    return cuts[i];
  }

  /** The cut of seam i: where the lower piece takes over from the upper, and why. */
  synchronized Cut.Row cut(int i) {
    if (rows[i] == null) {
      resolveCuts();
    }
    return rows[i];
  }

  /**
   * Every seam's cut. A cut placed by hand stays inside its own overlap, and in order down the leg
   * with its neighbours, {@link #FEATHER_MM} clear of each: the compositor takes piece i between
   * cut i-1 and cut i, so two cuts out of order leave the piece between them out and average its
   * neighbours there, which are lined up only through it (review of 11 Nov 2026: on three pieces
   * that all overlap, seam 1's line dragged below seam 2's made the middle piece vanish in that
   * band). An automatic cut stays where it is; a hand cut stops short of the cut above it and of
   * every automatic cut below it, leaving room for the hand cuts between. Where its own overlap and
   * its neighbours leave it no room at all, the hand position is given up and the cut is found
   * automatically. {@link #cutsInOrder} tells whether all that left the cuts in order.
   */
  private void resolveCuts() {
    int n = seams.size();
    List<Rigid> poses = poses();
    Cut.Row[] found = new Cut.Row[n];
    double[] frame = new double[n];
    for (int i = 0; i < n; i++) {
      if (Double.isNaN(cuts[i])) {
        found[i] = automaticCut(i);
        frame[i] = onFrame(poses, i, found[i].yMm());
      }
    }
    for (int i = 0; i < n; i++) {
      if (!Double.isNaN(cuts[i])) {
        double[] own = overlapRows(i);
        double lo = onFrame(poses, i, own[0]);
        double hi = onFrame(poses, i, own[1]);
        int above = -1;
        int below = -1;
        if (i > 0 && frame[i - 1] + FEATHER_MM > lo) {
          lo = frame[i - 1] + FEATHER_MM;
          above = i - 1;
        }
        for (int j = i + 1; j < n; j++) {
          if (Double.isNaN(cuts[j]) && frame[j] - (j - i) * FEATHER_MM < hi) {
            hi = frame[j] - (j - i) * FEATHER_MM;
            below = j;
          }
        }
        double asked = Math.max(own[0], Math.min(own[1], cuts[i]));
        double at = onFrame(poses, i, asked);
        if (lo > hi + 1e-9) {
          found[i] = automaticCut(i);
          found[i] =
              new Cut.Row(
                  found[i].yMm(),
                  "no room for the hand position between the neighbouring cuts: "
                      + found[i].note(),
                  found[i].clean());
        } else if (at < lo) {
          // down the piece is down the frame: a film turns a few degrees at most
          double y = asked + (lo - at) / Math.cos(poses.get(i).theta());
          found[i] =
              new Cut.Row(
                  y,
                  above < 0
                      ? "cut placed by hand"
                      : "cut placed by hand, held clear of seam %d's cut".formatted(above + 1),
                  false);
        } else if (at > hi) {
          double y = asked + (hi - at) / Math.cos(poses.get(i).theta());
          found[i] =
              new Cut.Row(
                  y,
                  below < 0
                      ? "cut placed by hand"
                      : "cut placed by hand, held clear of seam %d's cut".formatted(below + 1),
                  false);
        } else {
          found[i] = new Cut.Row(asked, "cut placed by hand", false);
        }
        frame[i] = onFrame(poses, i, found[i].yMm());
      }
    }
    System.arraycopy(found, 0, rows, 0, n);
  }

  private Cut.Row automaticCut(int i) {
    Piece upper = pieces.get(i);
    Piece lower = pieces.get(i + 1);
    return Cut.find(
        upper.match(), lower.match(), upper.heightMm(), lower.widthMm(), seams.get(i).transform());
  }

  /** A row of piece i, in its mm, as a height on the stitch frame at the piece's middle. */
  private double onFrame(List<Rigid> poses, int i, double yMm) {
    return poses.get(i).apply(pieces.get(i).widthMm() / 2, yMm)[1];
  }

  /**
   * The first and last row of seam i's upper piece a hand cut may take: inside the overlap, a
   * feather from each of its ends. The overlap ends where the lower piece does when that comes
   * before the upper piece's own end.
   */
  private double[] overlapRows(int i) {
    Piece upper = pieces.get(i);
    double[] box =
        SeamMatcher.overlap(
            upper.widthMm(), upper.heightMm(), seams.get(i).transform(), pieces.get(i + 1).match());
    double first = box[1] + FEATHER_MM;
    double last = Math.max(first, Math.min(upper.heightMm(), box[3]) - FEATHER_MM);
    return new double[] {first, last};
  }

  /**
   * True when every seam's cut lies below the cut of the seam above it on the stitch frame, which
   * the compositor needs. Hand cuts are held in order; automatic cuts of pieces that all overlap
   * could still cross, and then no picture may be made of the stitch.
   */
  boolean cutsInOrder() {
    for (int i = 1; i < seams.size(); i++) {
      if (!(seamY(i) > seamY(i - 1))) {
        return false;
      }
    }
    return true;
  }

  boolean anyFailed() {
    return seams.stream().anyMatch(s -> s.status() == Seam.Status.FAILED);
  }

  /** Automatic when every seam is, manual when every seam is, otherwise semi-automatic. */
  Seam.Method method() {
    return method(seams);
  }

  static Seam.Method method(List<Seam> seams) {
    if (seams.stream().allMatch(s -> s.method() == Seam.Method.AUTOMATIC)) {
      return Seam.Method.AUTOMATIC;
    }
    if (seams.stream().allMatch(s -> s.method() == Seam.Method.MANUAL)) {
      return Seam.Method.MANUAL;
    }
    return Seam.Method.SEMI_AUTOMATIC;
  }

  /**
   * True when the pieces offer one defensible scale: every piece has spacing on the same basis.
   * Otherwise the output carries no PixelSpacing (design section 5).
   */
  boolean hasScale() {
    if (unverified) {
      return false; // nothing may be measured on an unverified stitch
    }
    Spacing.Basis b = pieces.get(0).spacing.basis();
    return b != Spacing.Basis.NONE && pieces.stream().allMatch(p -> p.spacing.basis() == b);
  }

  /** The seams that failed, as "seam 2, seam 3". */
  String failedSeams() {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < seams.size(); i++) {
      if (seams.get(i).status() == Seam.Status.FAILED) {
        out.add("seam " + (i + 1));
      }
    }
    return String.join(", ", out);
  }

  /**
   * True when some pieces carry spacing and others do not: their millimetres and working pixels
   * cannot be joined, so the window refuses to line them up.
   */
  static boolean mixesScaledAndUnscaled(List<Piece> pieces) {
    return pieces.stream().anyMatch(p -> p.spacing.hasScale())
        && pieces.stream().anyMatch(p -> !p.spacing.hasScale());
  }

  /** Why the scale cannot be given, when it cannot. */
  Optional<String> noScaleReason() {
    if (hasScale()) {
      return Optional.empty();
    }
    if (unverified) {
      return Optional.of("an unverified stitch carries no scale, so nothing can be measured on it");
    }
    if (pieces.stream().allMatch(p -> !p.spacing.hasScale())) {
      return Optional.of("the pieces carry no pixel spacing");
    }
    return Optional.of(
        "the pieces' spacing comes from different sources ("
            + String.join(", ", pieces.stream().map(p -> p.spacing.basis().words).toList())
            + "), so no single scale is defensible");
  }

  /** Each piece's pose on the stitch frame (piece 0's millimetres): frame = pose(piece mm). */
  List<Rigid> poses() {
    List<Rigid> out = new ArrayList<>();
    Rigid pose = Rigid.IDENTITY;
    out.add(pose);
    for (Seam s : seams) {
      pose = pose.after(s.transform());
      out.add(pose);
    }
    return out;
  }

  /** {minX, minY, maxX, maxY} of all pieces on the stitch frame, in mm. */
  double[] bounds() {
    List<Rigid> poses = poses();
    double[] b = {
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    for (int i = 0; i < pieces.size(); i++) {
      Piece p = pieces.get(i);
      for (double[] c :
          new double[][] {
            {0, 0}, {p.widthMm(), 0}, {0, p.heightMm()}, {p.widthMm(), p.heightMm()}
          }) {
        double[] q = poses.get(i).apply(c[0], c[1]);
        b[0] = Math.min(b[0], q[0]);
        b[1] = Math.min(b[1], q[1]);
        b[2] = Math.max(b[2], q[0]);
        b[3] = Math.max(b[3], q[1]);
      }
    }
    return b;
  }

  /**
   * Output pixel size: the finest piece spacing, never finer than 0.15 mm, coarsened until the
   * image holds at most 32 megapixels (design section 6).
   */
  double outputMm() {
    double finest =
        pieces.stream()
            .mapToDouble(p -> Math.min(p.spacing.colMm(), p.spacing.rowMm()))
            .min()
            .orElse(1);
    double mm = hasScale() ? Math.max(finest, MIN_OUTPUT_MM) : finest;
    double[] b = bounds();
    double area = (b[2] - b[0]) * (b[3] - b[1]);
    return Math.max(mm, Math.sqrt(area / MAX_PIXELS) * 1.0001);
  }

  int columns() {
    double[] b = bounds();
    return (int) Math.ceil((b[2] - b[0]) / outputMm());
  }

  int rows() {
    double[] b = bounds();
    return (int) Math.ceil((b[3] - b[1]) / outputMm());
  }

  /** Where seam i's cut lies on the stitch frame, as a height in mm. */
  double seamY(int i) {
    Piece upper = pieces.get(i);
    return poses().get(i).apply(upper.widthMm() / 2, cut(i).yMm())[1];
  }

  /** Grey mapping per piece, value' = a * value + b, chaining each piece onto the one above. */
  List<double[]> exposure() {
    List<double[]> maps = new ArrayList<>();
    maps.add(new double[] {1, 0});
    for (int i = 0; i < seams.size(); i++) {
      double[] fit = fitExposure(pieces.get(i), pieces.get(i + 1), seams.get(i).transform());
      double[] up = maps.get(i);
      maps.add(new double[] {up[0] * fit[0], up[0] * fit[1] + up[1]});
    }
    return maps;
  }

  /**
   * Robust linear fit of the upper piece's values against the lower piece's at the same points of
   * the overlap; a fit with a negative or absurd slope falls back to matching medians.
   */
  static double[] fitExposure(Piece upper, Piece lower, Rigid lowerToUpper) {
    Gray u = upper.level();
    Gray l = lower.level();
    Gray um = upper.match().coarse();
    Gray lm = lower.match().coarse();
    Rigid toLower = lowerToUpper.inverse();
    List<double[]> pairs = new ArrayList<>();
    for (int y = 0; y < u.height(); y++) {
      for (int x = 0; x < u.width(); x++) {
        double px = (x + 0.5) * u.mm();
        double py = (y + 0.5) * u.mm();
        double[] q = toLower.apply(px, py);
        float a = u.sample(px, py);
        float b = l.sample(q[0], q[1]);
        if (Float.isNaN(a)
            || Float.isNaN(b)
            || Float.isNaN(um.sample(px, py))
            || Float.isNaN(lm.sample(q[0], q[1]))) {
          continue;
        }
        pairs.add(new double[] {b, a});
      }
    }
    if (pairs.size() < 20) {
      return new double[] {1, 0};
    }
    double[] fit = lineFit(pairs);
    double[] res =
        pairs.stream().mapToDouble(p -> Math.abs(p[1] - fit[0] * p[0] - fit[1])).toArray();
    double mad = median(res);
    List<double[]> kept =
        pairs.stream()
            .filter(p -> Math.abs(p[1] - fit[0] * p[0] - fit[1]) <= 3 * mad + 1e-9)
            .toList();
    double[] refit = kept.size() >= 20 ? lineFit(kept) : fit;
    if (!(refit[0] > 0.05 && refit[0] < 20)) {
      double mu = median(pairs.stream().mapToDouble(p -> p[1]).toArray());
      double ml = median(pairs.stream().mapToDouble(p -> p[0]).toArray());
      return new double[] {1, mu - ml};
    }
    return refit;
  }

  /** The draft label burned into the image, line by line (design section 7). */
  List<String> labelLines() {
    List<String> lines = new ArrayList<>();
    if (unverified) {
      lines.add(
          "UNVERIFIED STITCH: %s failed. Not for lengths, angles or templating."
              .formatted(failedSeams().isEmpty() ? "a seam" : failedSeams()));
    }
    lines.add("Stitched draft, %s, not reviewed.".formatted(method().word));
    lines.add("%s leg. Lengths and angles across seams unverified.".formatted(choices.leg().word));
    if (!hasScale()) {
      lines.add("No scale: lengths are not in mm.");
    }
    return lines;
  }

  /** SeriesDescription: contains LONG so the templating page takes it as a long film. */
  String seriesDescription() {
    return unverified
        ? "Long leg stitched (UNVERIFIED draft, not for measurement)"
        : "Long leg stitched (draft, %s, not reviewed)".formatted(method().word);
  }

  /** The derivation text: who joined what, how well, on what scale. */
  String derivation() {
    StringBuilder sb =
        new StringBuilder(
            (unverified
                    ? "UNVERIFIED (%s failed), saved for viewing only. ".formatted(failedSeams())
                    : "")
                + "Stitched draft from %d images by %s; %s, %s; %s leg, matched on the %s; output %s. "
                    .formatted(
                        pieces.size(),
                        StitchWriter.SOFTWARE,
                        choices.projection(),
                        choices.position(),
                        choices.leg().word,
                        choices.band().words(),
                        hasScale()
                            ? String.format(Locale.ROOT, "%.3f mm per pixel", outputMm())
                            : "without scale"));
    for (int i = 0; i < seams.size(); i++) {
      sb.append("Seam %d: %s; %s. ".formatted(i + 1, seams.get(i).summary(), cut(i).note()));
    }
    for (int i = 0; i < pieces.size(); i++) {
      sb.append("Piece %d: %s. ".formatted(i + 1, pieces.get(i).spacing.note()));
    }
    return sb.toString().trim();
  }

  private static double[] lineFit(List<double[]> pairs) {
    double sx = 0;
    double sy = 0;
    double sxx = 0;
    double sxy = 0;
    for (double[] p : pairs) {
      sx += p[0];
      sy += p[1];
      sxx += p[0] * p[0];
      sxy += p[0] * p[1];
    }
    int n = pairs.size();
    double den = n * sxx - sx * sx;
    if (Math.abs(den) < 1e-12) {
      return new double[] {1, (sy - sx) / n};
    }
    double a = (n * sxy - sx * sy) / den;
    return new double[] {a, (sy - a * sx) / n};
  }

  static double median(double[] v) {
    if (v.length == 0) {
      return Double.NaN;
    }
    double[] s = v.clone();
    Arrays.sort(s);
    return s[s.length / 2];
  }
}
