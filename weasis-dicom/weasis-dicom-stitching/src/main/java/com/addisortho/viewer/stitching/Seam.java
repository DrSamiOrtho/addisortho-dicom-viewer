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
import java.util.Locale;

/**
 * How one piece joins the piece above it. {@code transform} maps the lower piece's millimetres to
 * the upper piece's. Measures are NaN when unknown.
 *
 * @param rmsMm residual of the fit
 * @param rotationSigmaDeg one-sigma rotation uncertainty of the fit
 * @param shiftSigmaMm one-sigma shift uncertainty at the matched points' centre
 * @param scale the scale a similarity fit would give (a check, not applied)
 * @param uniqueness best coarse correlation peak over the best one outside its basin
 * @param holdoutMm median residual of the held-back patches
 * @param pairs the point pairs the fit used (matched patches or the user's clicks)
 * @param reasons why the seam is "check" or "failed", in words for the user
 */
record Seam(
    Rigid transform,
    Method method,
    Status status,
    double rmsMm,
    double rotationSigmaDeg,
    double shiftSigmaMm,
    double scale,
    double uniqueness,
    double holdoutMm,
    List<Rigid.Pair> pairs,
    List<String> reasons) {

  /** How the seam was set. */
  enum Method {
    AUTOMATIC("automatic"),
    SEMI_AUTOMATIC("semi-automatic"),
    MANUAL("manual");

    final String word;

    Method(String word) {
      this.word = word;
    }
  }

  /** Confidence word. Nothing is "good" until thresholds are measured (design section 5.2). */
  enum Status {
    CHECK("check"),
    FAILED("failed");

    final String word;

    Status(String word) {
      this.word = word;
    }
  }

  Seam {
    pairs = List.copyOf(pairs);
    reasons = List.copyOf(reasons);
  }

  static Seam failed(Rigid transform, Method method, List<String> reasons) {
    return new Seam(
        transform,
        method,
        Status.FAILED,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        List.of(),
        reasons);
  }

  /** A hand move this far from the matched position is a small correction; further is not. */
  static final double MAX_HAND_MOVE_MM = 2.0;

  static final double MAX_HAND_TURN_DEG = 0.3;

  /**
   * This seam, as a match left it, moved by hand (drag, nudge or rotation): now semi-automatic
   * unless it was manual. A failed seam stays failed (design section 5.4: manual adjustment never
   * clears a warning by itself); only the automatic match refined from the hand-set position,
   * matching points that agree, or a new automatic match can clear it. A "check" seam stays "check"
   * only for a small correction: moved more than {@link #MAX_HAND_MOVE_MM} at the overlap, or
   * turned more than {@link #MAX_HAND_TURN_DEG}, it is no longer the seam the match checked and
   * fails (review of 11 Nov 2026: a seam dragged 35 mm and turned 8 deg stayed "check" and could
   * be saved as measurable).
   *
   * @param refX a point of the lower piece, in its mm, where the move is measured: the middle of
   *     the overlap
   */
  Seam adjusted(Rigid moved, double refX, double refY) {
    Method m = method == Method.MANUAL ? Method.MANUAL : Method.SEMI_AUTOMATIC;
    double[] was = transform.apply(refX, refY);
    double[] now = moved.apply(refX, refY);
    double mm = Math.hypot(now[0] - was[0], now[1] - was[1]);
    double deg = Math.abs(moved.degrees() - transform.degrees());
    boolean far = mm > MAX_HAND_MOVE_MM + 1e-9 || deg > MAX_HAND_TURN_DEG + 1e-9;
    Status moveStatus = status == Status.FAILED || far ? Status.FAILED : Status.CHECK;
    List<String> why =
        List.of(
            status == Status.FAILED
                ? "moved by hand after a failed match; the seam stays failed until Refine"
                    + " automatically from here confirms it, or matching points that agree set it"
                    + " (Fit to the points)"
                : far
                    ? String.format(
                        Locale.ROOT,
                        "moved by hand %.1f mm and turned %.2f deg from where the match put it"
                            + " (a small correction is at most %.0f mm and %.1f deg): this is no"
                            + " longer the seam the match checked. Press Refine automatically from"
                            + " here to check it, or set it by matching points (Fit to the points)",
                        mm,
                        deg,
                        MAX_HAND_MOVE_MM,
                        MAX_HAND_TURN_DEG)
                    : "moved by hand: the residual is no longer measured; press Refine"
                        + " automatically from here to measure it, or inspect the overlap",
            "%s seam, not reviewed".formatted(m.word));
    return new Seam(
        moved,
        m,
        moveStatus,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        uniqueness,
        Double.NaN,
        List.of(),
        why);
  }

  /** One line for the window and the DICOM derivation text. */
  String summary() {
    double[] mid = transform.apply(0, 0);
    StringBuilder sb =
        new StringBuilder(
            String.format(
                Locale.ROOT,
                "%s, %s; shift %.1f, %.1f mm; rotation %.2f deg",
                method.word,
                status.word,
                mid[0],
                mid[1],
                transform.degrees()));
    if (!Double.isNaN(rmsMm)) {
      sb.append(String.format(Locale.ROOT, "; residual %.2f mm", rmsMm));
    }
    if (!Double.isNaN(rotationSigmaDeg)) {
      sb.append(
          String.format(
              Locale.ROOT,
              "; registration uncertainty %.2f deg, %.2f mm",
              rotationSigmaDeg,
              shiftSigmaMm));
    }
    if (!Double.isNaN(scale)) {
      sb.append(String.format(Locale.ROOT, "; scale check %.3f", scale));
    }
    if (!pairs.isEmpty()) {
      sb.append("; ").append(pairs.size()).append(" point pairs");
    }
    return sb.toString();
  }
}
