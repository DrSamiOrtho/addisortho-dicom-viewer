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
 * A rigid 2D transform in millimetres: p' = R(theta) p + (tx, ty). Scale is locked to 1 (design
 * section 5): a fitted scale would change every length inside the moved piece.
 */
record Rigid(double theta, double tx, double ty) {

  static final Rigid IDENTITY = new Rigid(0, 0, 0);

  /** A point pair: {@code from} in the moved piece, {@code to} in the fixed one, both in mm. */
  record Pair(double fx, double fy, double tx, double ty) {}

  static Rigid shift(double dx, double dy) {
    return new Rigid(0, dx, dy);
  }

  double[] apply(double x, double y) {
    double c = Math.cos(theta);
    double s = Math.sin(theta);
    return new double[] {c * x - s * y + tx, s * x + c * y + ty};
  }

  Rigid inverse() {
    double c = Math.cos(theta);
    double s = Math.sin(theta);
    return new Rigid(-theta, -(c * tx + s * ty), -(-s * tx + c * ty));
  }

  /** This after {@code first}: p -> this(first(p)). */
  Rigid after(Rigid first) {
    double[] t = apply(first.tx, first.ty);
    return new Rigid(theta + first.theta, t[0], t[1]);
  }

  double degrees() {
    return Math.toDegrees(theta);
  }

  double residual(Pair p) {
    double[] q = apply(p.fx, p.fy);
    return Math.hypot(q[0] - p.tx, q[1] - p.ty);
  }

  double rms(List<Pair> pairs) {
    if (pairs.isEmpty()) {
      return 0;
    }
    double s = 0;
    for (Pair p : pairs) {
      double r = residual(p);
      s += r * r;
    }
    return Math.sqrt(s / pairs.size());
  }

  /** Least-squares rigid fit (one pair: a shift; two or more: rotation and shift). */
  static Rigid fit(List<Pair> pairs) {
    if (pairs.isEmpty()) {
      throw new IllegalArgumentException("no pairs");
    }
    double[] c = centroids(pairs);
    if (pairs.size() == 1) {
      return shift(c[2] - c[0], c[3] - c[1]);
    }
    double sxx = 0;
    double sxy = 0;
    for (Pair p : pairs) {
      double ax = p.fx - c[0];
      double ay = p.fy - c[1];
      double bx = p.tx - c[2];
      double by = p.ty - c[3];
      sxx += ax * bx + ay * by;
      sxy += ax * by - ay * bx;
    }
    double theta = Math.atan2(sxy, sxx);
    double cs = Math.cos(theta);
    double sn = Math.sin(theta);
    return new Rigid(theta, c[2] - (cs * c[0] - sn * c[1]), c[3] - (sn * c[0] + cs * c[1]));
  }

  /**
   * The scale a similarity fit would give: a check only, never applied by itself (design section 5:
   * a difference over 1 percent makes the seam "check").
   */
  static double similarityScale(List<Pair> pairs) {
    if (pairs.size() < 2) {
      return 1;
    }
    double[] c = centroids(pairs);
    double sa = 0;
    double sxx = 0;
    double sxy = 0;
    for (Pair p : pairs) {
      double ax = p.fx - c[0];
      double ay = p.fy - c[1];
      double bx = p.tx - c[2];
      double by = p.ty - c[3];
      sa += ax * ax + ay * ay;
      sxx += ax * bx + ay * by;
      sxy += ax * by - ay * bx;
    }
    return sa <= 0 ? 1 : Math.hypot(sxx, sxy) / sa;
  }

  /**
   * One-sigma uncertainty of the fitted rotation (degrees) and of the shift at the pairs' centre
   * (mm), from the residual spread and the spread of the points. Registration only: it does not
   * include acquisition error such as parallax or magnification (design section 8).
   */
  static double[] uncertainty(Rigid r, List<Pair> pairs) {
    int n = pairs.size();
    if (n < 3) {
      return new double[] {Double.NaN, Double.NaN};
    }
    double[] c = centroids(pairs);
    double spread = 0;
    for (Pair p : pairs) {
      spread += (p.fx - c[0]) * (p.fx - c[0]) + (p.fy - c[1]) * (p.fy - c[1]);
    }
    double sigma = r.rms(pairs) * Math.sqrt(n / (n - 2.0));
    double rot = spread <= 0 ? Double.NaN : Math.toDegrees(sigma / Math.sqrt(spread));
    return new double[] {rot, sigma / Math.sqrt(n)};
  }

  private static double[] centroids(List<Pair> pairs) {
    double fx = 0;
    double fy = 0;
    double tx = 0;
    double ty = 0;
    for (Pair p : pairs) {
      fx += p.fx;
      fy += p.fy;
      tx += p.tx;
      ty += p.ty;
    }
    int n = pairs.size();
    return new double[] {fx / n, fy / n, tx / n, ty / n};
  }
}
