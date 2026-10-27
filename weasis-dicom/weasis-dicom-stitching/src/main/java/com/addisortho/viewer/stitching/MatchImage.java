/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

/**
 * The disposable copies of one piece used only for matching (design section 5): resampled to square
 * millimetre grids, MONOCHROME1 turned so bone is bright, clipped and scaled to 0..1, flat regions
 * masked. The output's grey values never come from these.
 *
 * @param coarse 2 mm grid, normalised and masked
 * @param coarseGrad gradient magnitude of {@code coarse}
 * @param fine 0.5 mm grid, normalised, masked where {@code coarse} is
 * @param fineGrad gradient magnitude of {@code fine}
 */
record MatchImage(Gray coarse, Gray coarseGrad, Gray fine, Gray fineGrad) {

  static final double COARSE_MM = 2.0;
  static final double FINE_MM = 0.5;

  /** Flat-region threshold on the 2 mm grid, as a fraction of the 0..1 range. */
  private static final double FLAT_STD = 0.0015;

  /**
   * @param values modality values, row-major
   * @param colMm horizontal pixel size in mm
   * @param rowMm vertical pixel size in mm
   * @param inverse true for MONOCHROME1 (bright means less dense)
   */
  static MatchImage of(float[] values, int w, int h, double colMm, double rowMm, boolean inverse) {
    float[] v = values;
    if (inverse) {
      v = new float[values.length];
      for (int i = 0; i < v.length; i++) {
        v[i] = -values[i];
      }
    }
    Gray coarse = Gray.resample(v, w, h, colMm, rowMm, COARSE_MM).normalized().maskFlat(FLAT_STD);
    Gray fine = Gray.resample(v, w, h, colMm, rowMm, FINE_MM).normalized();
    float[] f = fine.data();
    for (int y = 0; y < fine.height(); y++) {
      for (int x = 0; x < fine.width(); x++) {
        if (Float.isNaN(coarse.sample((x + 0.5) * FINE_MM, (y + 0.5) * FINE_MM))) {
          f[y * fine.width() + x] = Float.NaN;
        }
      }
    }
    return new MatchImage(coarse, coarse.gradientMagnitude(), fine, fine.gradientMagnitude());
  }

  double widthMm() {
    return fine.widthMm();
  }

  double heightMm() {
    return fine.heightMm();
  }
}
