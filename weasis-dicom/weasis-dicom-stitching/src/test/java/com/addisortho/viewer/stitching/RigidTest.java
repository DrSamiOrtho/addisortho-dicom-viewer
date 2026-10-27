/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RigidTest {

  private static List<Rigid.Pair> pairs(Rigid r, double... xy) {
    List<Rigid.Pair> out = new ArrayList<>();
    for (int i = 0; i < xy.length; i += 2) {
      double[] q = r.apply(xy[i], xy[i + 1]);
      out.add(new Rigid.Pair(xy[i], xy[i + 1], q[0], q[1]));
    }
    return out;
  }

  @Test
  void inverseAndCompositionUndoEachOther() {
    Rigid r = new Rigid(Math.toRadians(2), 5, -7);
    Rigid s = new Rigid(Math.toRadians(-1), 3, 300);
    assertArrayEquals(new double[] {12, 34}, r.inverse().after(r).apply(12, 34), 1e-9);
    double[] a = r.after(s).apply(12, 34);
    double[] b = s.apply(12, 34);
    assertArrayEquals(r.apply(b[0], b[1]), a, 1e-9);
  }

  @Test
  void onePairGivesAShiftTwoGiveRotation() {
    Rigid truth = new Rigid(Math.toRadians(1.5), 4, 310);
    Rigid one = Rigid.fit(pairs(truth, 100, 20));
    assertEquals(0, one.theta(), 1e-12);
    Rigid two = Rigid.fit(pairs(truth, 20, 10, 200, 30));
    assertEquals(truth.degrees(), two.degrees(), 1e-9);
    assertArrayEquals(truth.apply(50, 50), two.apply(50, 50), 1e-9);
  }

  @Test
  void scaleIsMeasuredNotApplied() {
    List<Rigid.Pair> scaled = new ArrayList<>();
    for (double[] p : new double[][] {{0, 0}, {100, 0}, {0, 50}, {100, 50}}) {
      scaled.add(new Rigid.Pair(p[0], p[1], p[0] * 1.02, p[1] * 1.02 + 300));
    }
    assertEquals(1.02, Rigid.similarityScale(scaled), 1e-9);
    assertTrue(Rigid.fit(scaled).rms(scaled) > 0.5, "a rigid fit cannot absorb the scale");
  }

  @Test
  void uncertaintyNeedsThreePairs() {
    Rigid truth = Rigid.shift(1, 2);
    assertTrue(Double.isNaN(Rigid.uncertainty(truth, pairs(truth, 0, 0, 10, 0))[0]));
    double[] u = Rigid.uncertainty(truth, pairs(truth, 0, 0, 100, 0, 50, 40));
    assertEquals(0, u[0], 1e-12);
  }
}
