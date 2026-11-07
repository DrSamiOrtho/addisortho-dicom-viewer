/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Where the cut between two overlapping pieces goes (Sami, 7 Nov 2026: never through a sphere). A
 * calibration ball, a femoral head, the condyles or the greater trochanter must come whole from one
 * piece. So the cut row is placed where the bone outline runs straight, on a shaft, whose edges are
 * vertical so the vertical gradient is small against the horizontal one; a round outline (a head,
 * condyle, trochanter, plateau) has a large vertical gradient and is avoided. Rows through or near
 * a bright round marker in either piece (a calibration ball, a prosthetic head) are never used. The
 * cut stays away from the feathered ends of the overlap. The user can still drag it.
 */
final class Cut {

  /** The cut row in the upper piece's millimetres, why, and whether it lies on straight bone. */
  record Row(double yMm, String note, boolean clean) {}

  /** Rows this close to the overlap's ends are not cut: the feather would reach the edge. */
  static final double MARGIN_MM = Stitch.FEATHER_MM + 5;

  /** The rows that count for one candidate cut: the feather and a little more. */
  static final double WINDOW_MM = Stitch.FEATHER_MM + 4;

  /** A round marker's rows plus this on each side are never cut. */
  static final double BALL_MARGIN_MM = Stitch.FEATHER_MM + 4;

  /** Vertical over horizontal gradient on bone below this: the outline runs straight. */
  static final double STRAIGHT = 0.6;

  // a bright round marker on the 2 mm copy: bright, compact, round, and standing out from its
  // surroundings. Not "saturated": on the 7 Nov 2026 case the ball reached only 0.9 of the
  // normalised range on two pieces while dense bone reached 1.0.
  private static final float[] LEVELS = {0.85f, 0.75f, 0.65f};
  private static final int BALL_MIN_CELLS = 20; // about 10 mm across
  private static final int BALL_MAX_CELLS = 500; // about 50 mm across
  private static final double BALL_MIN_FILL = 0.55;
  private static final double BALL_MAX_ASPECT = 1.4;
  private static final double BALL_CONTRAST = 0.25; // over the ring two cells around it

  private Cut() {}

  /** The middle of the overlap: used when the pieces have no matching copies yet. */
  static Row middle(double upperH, Rigid lowerToUpper, double lowerW) {
    double[] tl = lowerToUpper.apply(0, 0);
    double[] tr = lowerToUpper.apply(lowerW, 0);
    double top = Math.max(0, Math.min(tl[1], tr[1]));
    return new Row((top + upperH) / 2, "cut in the middle of the overlap", false);
  }

  /**
   * The cut for one seam. {@code upper} and {@code lower} are the pieces' matching copies; either
   * may be null, in which case the middle of the overlap is used.
   */
  static Row find(
      MatchImage upper, MatchImage lower, double upperH, double lowerW, Rigid lowerToUpper) {
    if (upper == null || lower == null) {
      return middle(upperH, lowerToUpper, lowerW);
    }
    double[] box = SeamMatcher.overlap(upper.widthMm(), upperH, lowerToUpper, lower);
    double top = box[1];
    double bottom = box[3];
    double lo = top + MARGIN_MM;
    double hi = bottom - MARGIN_MM;
    if (hi <= lo) {
      return new Row((top + bottom) / 2, "cut in the middle of a short overlap", false);
    }
    List<double[]> avoid = new ArrayList<>();
    for (double[] b : balls(upper.full())) {
      avoid.add(new double[] {b[0] - BALL_MARGIN_MM, b[1] + BALL_MARGIN_MM});
    }
    for (double[] b : balls(lower.full())) {
      double y0 = lowerToUpper.apply(lowerW / 2, b[0])[1];
      double y1 = lowerToUpper.apply(lowerW / 2, b[1])[1];
      avoid.add(
          new double[] {Math.min(y0, y1) - BALL_MARGIN_MM, Math.max(y0, y1) + BALL_MARGIN_MM});
    }
    Gray u = upper.coarse();
    Gray l = lower.coarse();
    Gray[] ug = u.gradients();
    Gray[] lg = l.gradients();
    double mm = u.mm();
    double bright =
        SeamMatcher.otsu(
            u,
            (int) (box[0] / mm),
            (int) (top / mm),
            (int) Math.ceil(box[2] / mm),
            (int) Math.ceil(bottom / mm));
    double lowerBright = SeamMatcher.otsu(l, 0, 0, l.width(), l.height());
    Rigid toLower = lowerToUpper.inverse();
    double best = Double.NaN;
    double bestScore = Double.POSITIVE_INFINITY;
    double bestFree = Double.NaN; // the candidate farthest from any marker, if all are near one
    double bestFreeDist = -1;
    for (double y = lo; y <= hi; y += mm) {
      double gyUp = 0;
      double gxUp = 0;
      for (double r = y - WINDOW_MM / 2; r <= y + WINDOW_MM / 2; r += mm) {
        int row = (int) (r / mm);
        if (row < 0 || row >= u.height()) {
          continue;
        }
        for (int x = (int) (box[0] / mm); x < Math.min(u.width(), Math.ceil(box[2] / mm)); x++) {
          float v = u.at(x, row);
          float gx = ug[0].at(x, row);
          float gy = ug[1].at(x, row);
          if (!Float.isNaN(v) && v >= bright && !Float.isNaN(gx) && !Float.isNaN(gy)) {
            gyUp += Math.abs(gy);
            gxUp += Math.abs(gx);
          }
          double[] q = toLower.apply((x + 0.5) * mm, r);
          int lx = (int) (q[0] / mm);
          int ly = (int) (q[1] / mm);
          if (lx >= 0 && ly >= 0 && lx < l.width() && ly < l.height()) {
            float lv = l.at(lx, ly);
            float lgx = lg[0].at(lx, ly);
            float lgy = lg[1].at(lx, ly);
            if (!Float.isNaN(lv) && lv >= lowerBright && !Float.isNaN(lgx) && !Float.isNaN(lgy)) {
              gyUp += Math.abs(lgy);
              gxUp += Math.abs(lgx);
            }
          }
        }
      }
      double score = gyUp / (gxUp + 1e-6);
      double dist = Double.POSITIVE_INFINITY;
      for (double[] a : avoid) {
        dist = Math.min(dist, y < a[0] ? a[0] - y : y > a[1] ? y - a[1] : 0);
      }
      if (dist > bestFreeDist) {
        bestFreeDist = dist;
        bestFree = y;
      }
      if (dist > 0 && score < bestScore) {
        bestScore = score;
        best = y;
      }
    }
    if (Double.isNaN(best)) {
      return new Row(
          bestFree,
          "a bright round marker fills the overlap: the cut is as far from it as it can be; drag"
              + " the seam line to move it",
          false);
    }
    boolean clean = bestScore < STRAIGHT;
    String note =
        clean
            ? String.format(
                Locale.ROOT,
                "cut %.0f mm below the overlap's top, on straight bone (vertical over horizontal"
                    + " edge %.2f)%s",
                best - top,
                bestScore,
                avoid.isEmpty() ? "" : ", clear of a round marker")
            : String.format(
                Locale.ROOT,
                "cut %.0f mm below the overlap's top crosses a round bone outline (vertical over"
                    + " horizontal edge %.2f; no straight shaft in the overlap): drag the seam line"
                    + " to move it",
                best - top,
                bestScore);
    return new Row(best, note, clean);
  }

  /** Mean value of the one-cell ring on the given box, inside the image; NaN cells left out. */
  private static double ring(Gray g, int x0, int y0, int x1, int y1) {
    double s = 0;
    int n = 0;
    for (int y = y0; y <= y1; y++) {
      for (int x = x0; x <= x1; x++) {
        boolean edge = x == x0 || x == x1 || y == y0 || y == y1;
        if (!edge || x < 0 || y < 0 || x >= g.width() || y >= g.height()) {
          continue;
        }
        float v = g.at(x, y);
        if (!Float.isNaN(v)) {
          s += v;
          n++;
        }
      }
    }
    return n == 0 ? 0 : s / n;
  }

  /**
   * Row ranges {top, bottom} in mm of bright round markers in an unmasked 2 mm copy. The copy is
   * thresholded at three levels, since a ball's interior is not uniformly bright after the
   * machine's processing (0.7 to 0.9 on the 7 Nov 2026 case); a compact round component that stands
   * out from its ring at any level is a marker, and one found at a higher level is not counted
   * again at a lower one.
   */
  static List<double[]> balls(Gray g) {
    List<double[]> out = new ArrayList<>();
    List<int[]> boxes = new ArrayList<>();
    int w = g.width();
    int h = g.height();
    for (float thr : LEVELS) {
      boolean[] seen = new boolean[w * h];
      for (int i = 0; i < seen.length; i++) {
        if (seen[i] || !(g.data()[i] >= thr)) {
          continue;
        }
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(i);
        seen[i] = true;
        int n = 0;
        double sum = 0;
        int x0 = w;
        int x1 = -1;
        int y0 = h;
        int y1 = -1;
        while (!stack.isEmpty()) {
          int k = stack.pop();
          int x = k % w;
          int y = k / w;
          n++;
          sum += g.data()[k];
          x0 = Math.min(x0, x);
          x1 = Math.max(x1, x);
          y0 = Math.min(y0, y);
          y1 = Math.max(y1, y);
          int[][] next = {{x - 1, y}, {x + 1, y}, {x, y - 1}, {x, y + 1}};
          for (int[] q : next) {
            if (q[0] < 0 || q[1] < 0 || q[0] >= w || q[1] >= h) {
              continue;
            }
            int m = q[1] * w + q[0];
            if (!seen[m] && g.data()[m] >= thr) {
              seen[m] = true;
              stack.push(m);
            }
          }
        }
        int bw = x1 - x0 + 1;
        int bh = y1 - y0 + 1;
        double aspect = (double) Math.max(bw, bh) / Math.min(bw, bh);
        double fill = (double) n / (bw * bh);
        boolean known = false;
        for (int[] b : boxes) {
          known |= x0 <= b[2] && x1 >= b[0] && y0 <= b[3] && y1 >= b[1];
        }
        if (!known
            && n >= BALL_MIN_CELLS
            && n <= BALL_MAX_CELLS
            && aspect <= BALL_MAX_ASPECT
            && fill >= BALL_MIN_FILL
            && sum / n - ring(g, x0 - 2, y0 - 2, x1 + 2, y1 + 2) >= BALL_CONTRAST) {
          boxes.add(new int[] {x0, y0, x1, y1});
          out.add(new double[] {y0 * g.mm(), (y1 + 1) * g.mm()});
        }
      }
    }
    return out;
  }
}
