/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.util.Random;

/**
 * A synthetic long-leg film on a 0.25 mm canvas, cut into overlapping pieces with known poses,
 * exposures and noise. Not anatomy: bright bands for the femur and tibia, a soft-tissue silhouette
 * and blob texture, enough to test that the matcher recovers a known transform.
 */
final class Phantom {

  static final double MM = 0.25;

  final Gray canvas;

  private Phantom(Gray canvas) {
    this.canvas = canvas;
  }

  /** Bones, soft tissue and blob texture. */
  static Phantom leg(double widthMm, double heightMm, long seed) {
    Gray g = Gray.blank((int) (widthMm / MM), (int) (heightMm / MM), MM);
    Random rnd = new Random(seed);
    float[] d = g.data();
    for (int y = 0; y < g.height(); y++) {
      double ym = (y + 0.5) * MM;
      double half = 60 + 15 * Math.sin(ym / 180);
      double boneHalf = 14 + 6 * Math.cos(ym / 70);
      double centre = widthMm / 2 + 10 * Math.sin(ym / 300);
      for (int x = 0; x < g.width(); x++) {
        double xm = (x + 0.5) * MM;
        double r = Math.abs(xm - centre);
        double v = 0.1 + 0.3 * smoothStep(half - r);
        v += 0.4 * smoothStep(boneHalf - r) * (0.8 + 0.2 * Math.cos(r / boneHalf * Math.PI));
        d[y * g.width() + x] = (float) v;
      }
    }
    int blobs = (int) (widthMm * heightMm / 40);
    for (int k = 0; k < blobs; k++) {
      splat(
          g,
          rnd.nextDouble() * widthMm,
          rnd.nextDouble() * heightMm,
          0.5 + rnd.nextDouble() * 2.5,
          (rnd.nextDouble() - 0.5) * 0.3);
    }
    return new Phantom(g);
  }

  /**
   * One leg in a wide film, as on a real single-leg plate: a bone about 30 mm wide with a flare
   * (condyles) mid-height, soft tissue around it, and texture on the bone only, none on skin or
   * background. The matched points can then only lie on the bone, a small part of the film.
   */
  static Phantom singleLeg(double widthMm, double heightMm, double legX, long seed) {
    Gray g = Gray.blank((int) (widthMm / MM), (int) (heightMm / MM), MM);
    java.util.Arrays.fill(g.data(), 0.1f);
    drawLeg(g, heightMm, legX, new Random(seed));
    return new Phantom(g);
  }

  /** Two such legs in one film, as on a bilateral standing plate. */
  static Phantom twoLegs(double widthMm, double heightMm, double leftX, double rightX, long seed) {
    Gray g = Gray.blank((int) (widthMm / MM), (int) (heightMm / MM), MM);
    java.util.Arrays.fill(g.data(), 0.1f);
    Random rnd = new Random(seed);
    drawLeg(g, heightMm, leftX, rnd);
    drawLeg(g, heightMm, rightX, rnd);
    return new Phantom(g);
  }

  private static void drawLeg(Gray g, double heightMm, double legX, Random rnd) {
    float[] d = g.data();
    double[] boneHalf = new double[g.height()];
    double[] centre = new double[g.height()];
    for (int y = 0; y < g.height(); y++) {
      double ym = (y + 0.5) * MM;
      double half = 55 + 10 * Math.sin(ym / 180);
      boneHalf[y] = 15 + 12 * Math.exp(-Math.pow((ym - heightMm / 2) / 40, 2));
      centre[y] = legX + 6 * Math.sin(ym / 300);
      for (int x = 0; x < g.width(); x++) {
        double r = Math.abs((x + 0.5) * MM - centre[y]);
        double v = 0.3 * smoothStep(half - r);
        v += 0.4 * smoothStep(boneHalf[y] - r) * (0.8 + 0.2 * Math.cos(r / boneHalf[y] * Math.PI));
        d[y * g.width() + x] += (float) v;
      }
    }
    int blobs = (int) (heightMm * 40 / 6);
    for (int k = 0; k < blobs; k++) {
      double ym = rnd.nextDouble() * heightMm;
      int row = Math.min(g.height() - 1, (int) (ym / MM));
      double xm = centre[row] + (rnd.nextDouble() * 2 - 1) * boneHalf[row] * 0.9;
      splat(g, xm, ym, 0.5 + rnd.nextDouble() * 1.5, (rnd.nextDouble() - 0.5) * 0.25);
    }
  }

  /** Bones and soft tissue only: straight vertical edges, nothing to fix a vertical shift. */
  static Phantom shaftOnly(double widthMm, double heightMm) {
    Gray g = Gray.blank((int) (widthMm / MM), (int) (heightMm / MM), MM);
    float[] d = g.data();
    for (int y = 0; y < g.height(); y++) {
      for (int x = 0; x < g.width(); x++) {
        double r = Math.abs((x + 0.5) * MM - widthMm / 2);
        d[y * g.width() + x] = (float) (0.1 + 0.3 * smoothStep(60 - r) + 0.4 * smoothStep(15 - r));
      }
    }
    return new Phantom(g);
  }

  /** A regular grid of dots every {@code pitchMm}: matches equally well one pitch away. */
  static Phantom dots(double widthMm, double heightMm, double pitchMm) {
    Gray g = Gray.blank((int) (widthMm / MM), (int) (heightMm / MM), MM);
    java.util.Arrays.fill(g.data(), 0.3f);
    for (double y = pitchMm / 2; y < heightMm; y += pitchMm) {
      for (double x = pitchMm / 2; x < widthMm; x += pitchMm) {
        splat(g, x, y, 1.5, 0.5);
      }
    }
    return new Phantom(g);
  }

  /** A piece's pose on the canvas: canvas = pose(piece mm). */
  record Cut(
      Rigid pose, double widthMm, double heightMm, double spacingMm, double gain, double offset) {}

  /** Samples the canvas through the cut's pose, with exposure gain, offset and Gaussian noise. */
  float[] piece(Cut c, double noise, long seed) {
    int w = (int) Math.round(c.widthMm / c.spacingMm);
    int h = (int) Math.round(c.heightMm / c.spacingMm);
    float[] out = new float[w * h];
    Random rnd = new Random(seed);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        double[] p = c.pose.apply((x + 0.5) * c.spacingMm, (y + 0.5) * c.spacingMm);
        float v = canvas.sample(p[0], p[1]);
        double base = Float.isNaN(v) ? 0 : v;
        out[y * w + x] = (float) (c.gain * (base + noise * rnd.nextGaussian()) + c.offset);
      }
    }
    return out;
  }

  MatchImage matchImage(Cut c, double noise, long seed) {
    int w = (int) Math.round(c.widthMm / c.spacingMm);
    int h = (int) Math.round(c.heightMm / c.spacingMm);
    return MatchImage.of(piece(c, noise, seed), w, h, c.spacingMm, c.spacingMm, false);
  }

  /**
   * A piece whose left half is cut through {@code left} and right half through {@code right}: the
   * two legs moved differently between exposures, as parallax or movement does.
   */
  MatchImage splicedMatchImage(Cut left, Cut right, double noise, long seed) {
    int w = (int) Math.round(left.widthMm / left.spacingMm);
    int h = (int) Math.round(left.heightMm / left.spacingMm);
    float[] a = piece(left, noise, seed);
    float[] b = piece(right, noise, seed + 1);
    for (int y = 0; y < h; y++) {
      System.arraycopy(b, y * w + w / 2, a, y * w + w / 2, w - w / 2);
    }
    return MatchImage.of(a, w, h, left.spacingMm, left.spacingMm, false);
  }

  /** The true seam between two cuts: lower piece mm to upper piece mm. */
  static Rigid truth(Cut upper, Cut lower) {
    return upper.pose.inverse().after(lower.pose);
  }

  private static double smoothStep(double d) {
    return 1 / (1 + Math.exp(-d / 1.5));
  }

  private static void splat(Gray g, double cx, double cy, double sigma, double amp) {
    int r = (int) Math.ceil(3 * sigma / MM);
    int x0 = (int) (cx / MM);
    int y0 = (int) (cy / MM);
    for (int y = Math.max(0, y0 - r); y <= Math.min(g.height() - 1, y0 + r); y++) {
      for (int x = Math.max(0, x0 - r); x <= Math.min(g.width() - 1, x0 + r); x++) {
        double dx = (x + 0.5) * MM - cx;
        double dy = (y + 0.5) * MM - cy;
        g.data()[y * g.width() + x] +=
            (float) (amp * Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)));
      }
    }
  }
}
