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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The cut never goes through a sphere, a condyle or a trochanter (Sami, 7 Nov 2026). */
class CutTest {

  private static final double MM = 1.0;
  private static final int W = 300;
  private static final int H = 400;

  /**
   * A film: soft tissue, two straight shafts, a bright ball at (bx, by) and a round condyle pair.
   */
  private static float[] film(double bx, double by, double condyleY) {
    float[] v = new float[W * H];
    for (int y = 0; y < H; y++) {
      for (int x = 0; x < W; x++) {
        double val = 200 + 20 * Math.sin(x * 0.05) + 15 * Math.cos(y * 0.07);
        for (double cx : new double[] {90, 210}) {
          double d = Math.abs(x - cx);
          if (d < 14) {
            val += d > 10 ? 600 : 350; // cortex and medulla of a straight shaft
          }
          // a round knob (condyle) at the shaft's end
          double r = Math.hypot(x - cx, y - condyleY);
          if (r < 32) {
            val += 500 + 200 * Math.cos(r / 32 * Math.PI / 2);
          }
        }
        if (Math.hypot(x - bx, y - by) < 13) {
          val += 4000; // the calibration ball: metal
        }
        v[y * W + x] = (float) val;
      }
    }
    return v;
  }

  @Test
  void findsTheBall() {
    MatchImage m = MatchImage.of(film(40, 330, 1000), W, H, MM, MM, false);
    List<double[]> balls = Cut.balls(m.full());
    assertEquals(1, balls.size());
    assertTrue(
        balls.get(0)[0] > 310 && balls.get(0)[1] < 350, balls.get(0)[0] + ".." + balls.get(0)[1]);
  }

  /**
   * Overlap rows 250 to 400 of the upper piece: a ball at 330 and condyles at 380 lie in it; the
   * cut must land on the shaft above the ball.
   */
  @Test
  void cutsTheShaftNotTheBallOrTheCondyle() {
    MatchImage upper = MatchImage.of(film(40, 330, 380), W, H, MM, MM, false);
    MatchImage lower = MatchImage.of(film(40, 80, 130), W, H, MM, MM, false);
    Cut.Row row = Cut.find(upper, lower, H, W, Rigid.shift(0, 250));
    assertTrue(row.clean(), row.note());
    assertTrue(row.yMm() >= 265 && row.yMm() <= 300, row.note());
    assertTrue(row.note().contains("round marker"), row.note());
  }

  /** The same film with the ball in the shaft's clear rows: the cut goes below or above it. */
  @Test
  void keepsClearOfTheBall() {
    MatchImage upper = MatchImage.of(film(40, 285, 1000), W, H, MM, MM, false);
    MatchImage lower = MatchImage.of(film(40, 35, 1000), W, H, MM, MM, false);
    Cut.Row row = Cut.find(upper, lower, H, W, Rigid.shift(0, 250));
    assertTrue(Math.abs(row.yMm() - 285) > 13 + Cut.BALL_MARGIN_MM - 1, row.note());
  }

  @Test
  void middleWithoutMatchingCopies() {
    Cut.Row row = Cut.find(null, null, H, W, Rigid.shift(0, 250));
    assertEquals(325, row.yMm(), 1e-9);
    assertFalse(row.clean());
  }
}
