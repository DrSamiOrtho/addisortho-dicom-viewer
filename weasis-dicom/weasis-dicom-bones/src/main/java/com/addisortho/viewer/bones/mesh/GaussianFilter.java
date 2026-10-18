/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

import java.util.concurrent.CancellationException;
import java.util.stream.IntStream;

/** Separable Gaussian smoothing of a {@link ScalarGrid}, in place, edges clamped. */
public final class GaussianFilter {

  private GaussianFilter() {}

  /**
   * @param sigmaMm standard deviation in mm; nothing happens when it is not positive
   * @throws CancellationException when the thread is interrupted
   */
  public static void smooth(ScalarGrid grid, double sigmaMm) {
    if (!(sigmaMm > 0)) {
      return;
    }
    int nx = grid.sizeX();
    int ny = grid.sizeY();
    int nz = grid.sizeZ();
    float[] v = grid.values();
    // x lines: one per (j, k); y lines: one per (i, k); z lines: one per (i, j).
    pass(v, kernel(sigmaMm / grid.spacingX()), nx, 1, ny * nz, line -> line * nx);
    pass(
        v,
        kernel(sigmaMm / grid.spacingY()),
        ny,
        nx,
        nx * nz,
        line -> {
          int k = line / nx;
          return k * nx * ny + line % nx;
        });
    pass(v, kernel(sigmaMm / grid.spacingZ()), nz, nx * ny, nx * ny, line -> line);
  }

  /** Normalised kernel of radius ceil(3 sigma) samples, centre at index radius. */
  static float[] kernel(double sigma) {
    int radius = Math.max(1, (int) Math.ceil(3 * sigma));
    float[] k = new float[2 * radius + 1];
    double sum = 0;
    for (int n = -radius; n <= radius; n++) {
      double w = Math.exp(-n * n / (2 * sigma * sigma));
      k[n + radius] = (float) w;
      sum += w;
    }
    for (int n = 0; n < k.length; n++) {
      k[n] /= (float) sum;
    }
    return k;
  }

  private interface LineStart {
    int offset(int line);
  }

  /** Convolves {@code lines} lines of {@code length} samples spaced {@code stride} apart. */
  private static void pass(
      float[] v, float[] kernel, int length, int stride, int lines, LineStart start) {
    if (length < 2) {
      return;
    }
    int radius = kernel.length / 2;
    // Pool threads are not interrupted with the caller, so they watch the caller's flag.
    Thread caller = Thread.currentThread();
    IntStream.range(0, lines)
        .parallel()
        .forEach(
            line -> {
              if (caller.isInterrupted()) {
                throw new CancellationException();
              }
              int offset = start.offset(line);
              float[] src = new float[length];
              for (int n = 0; n < length; n++) {
                src[n] = v[offset + n * stride];
              }
              for (int n = 0; n < length; n++) {
                float sum = 0;
                for (int m = -radius; m <= radius; m++) {
                  sum += kernel[m + radius] * src[Math.clamp(n + m, 0, length - 1)];
                }
                v[offset + n * stride] = sum;
              }
            });
  }
}
