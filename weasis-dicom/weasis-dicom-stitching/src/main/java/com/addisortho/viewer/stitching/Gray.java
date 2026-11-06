/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.util.Arrays;

/**
 * A grey image on a square grid of {@code mm} millimetres per pixel. Pixel (row, col) has its
 * centre at ((col + 0.5) mm, (row + 0.5) mm) in the piece's own millimetre frame. {@code NaN} marks
 * a pixel that must not drive matching (padding, collimator shutter).
 */
record Gray(int width, int height, double mm, float[] data) {

  Gray {
    if (data.length != width * height) {
      throw new IllegalArgumentException("size");
    }
  }

  static Gray blank(int width, int height, double mm) {
    return new Gray(width, height, mm, new float[width * height]);
  }

  float at(int col, int row) {
    return data[row * width + col];
  }

  double widthMm() {
    return width * mm;
  }

  double heightMm() {
    return height * mm;
  }

  /** Bilinear value at a point in millimetres, or NaN outside the image or next to a NaN. */
  float sample(double xMm, double yMm) {
    double fx = xMm / mm - 0.5;
    double fy = yMm / mm - 0.5;
    int x0 = (int) Math.floor(fx);
    int y0 = (int) Math.floor(fy);
    if (x0 < 0 || y0 < 0 || x0 + 1 >= width || y0 + 1 >= height) {
      return edgeSample(fx, fy);
    }
    double ax = fx - x0;
    double ay = fy - y0;
    int i = y0 * width + x0;
    double top = data[i] * (1 - ax) + data[i + 1] * ax;
    double bottom = data[i + width] * (1 - ax) + data[i + width + 1] * ax;
    return (float) (top * (1 - ay) + bottom * ay);
  }

  // the outer half pixel takes the nearest pixel's value instead of falling off the image
  private float edgeSample(double fx, double fy) {
    if (fx < -0.5 || fy < -0.5 || fx > width - 0.5 || fy > height - 0.5) {
      return Float.NaN;
    }
    int x = (int) Math.min(width - 1, Math.max(0, Math.round(fx)));
    int y = (int) Math.min(height - 1, Math.max(0, Math.round(fy)));
    return data[y * width + x];
  }

  /**
   * Area-averages a full-resolution image with its own pixel size onto a square grid. Separable,
   * with exact fractional overlaps, so pieces with different or non-square pixels meet at one
   * scale.
   *
   * @param src row-major source values
   * @param colMm source column spacing (horizontal pixel size) in mm
   * @param rowMm source row spacing (vertical pixel size) in mm
   * @param mm target grid in mm per pixel
   */
  static Gray resample(float[] src, int sw, int sh, double colMm, double rowMm, double mm) {
    int tw = Math.max(1, (int) Math.round(sw * colMm / mm));
    int th = Math.max(1, (int) Math.round(sh * rowMm / mm));
    Weights wx = Weights.of(sw, colMm, tw, mm);
    Weights wy = Weights.of(sh, rowMm, th, mm);
    float[] rows = new float[sh * tw];
    for (int r = 0; r < sh; r++) {
      wx.apply(src, r * sw, 1, rows, r * tw, 1);
    }
    float[] out = new float[th * tw];
    for (int c = 0; c < tw; c++) {
      wy.apply(rows, c, tw, out, c, tw);
    }
    return new Gray(tw, th, mm, out);
  }

  /** Values clipped to their 1st and 99th percentiles and scaled to 0..1 (NaN kept). */
  Gray normalized() {
    float[] finite = finiteValues();
    float[] out = data.clone();
    if (finite.length == 0) {
      return new Gray(width, height, mm, out);
    }
    Arrays.sort(finite);
    float lo = finite[(int) (finite.length * 0.01)];
    float hi = finite[Math.min(finite.length - 1, (int) (finite.length * 0.99))];
    float span = Math.max(hi - lo, 1e-6f);
    for (int i = 0; i < out.length; i++) {
      if (!Float.isNaN(out[i])) {
        out[i] = Math.min(1f, Math.max(0f, (out[i] - lo) / span));
      }
    }
    return new Gray(width, height, mm, out);
  }

  float[] finiteValues() {
    float[] v = new float[data.length];
    int n = 0;
    for (float f : data) {
      if (!Float.isNaN(f)) {
        v[n++] = f;
      }
    }
    return Arrays.copyOf(v, n);
  }

  /**
   * Marks flat regions (collimator shutters, padding, air outside the film) as NaN: pixels whose 5
   * by 5 neighbourhood varies less than {@code minStd} of the 0..1 range.
   */
  Gray maskFlat(double minStd) {
    float[] out = data.clone();
    int r = 2;
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < width; x++) {
        double s = 0;
        double s2 = 0;
        int n = 0;
        for (int j = Math.max(0, y - r); j <= Math.min(height - 1, y + r); j++) {
          for (int i = Math.max(0, x - r); i <= Math.min(width - 1, x + r); i++) {
            float v = data[j * width + i];
            if (!Float.isNaN(v)) {
              s += v;
              s2 += v * v;
              n++;
            }
          }
        }
        double var = n < 2 ? 0 : s2 / n - (s / n) * (s / n);
        if (var < minStd * minStd) {
          out[y * width + x] = Float.NaN;
        }
      }
    }
    return new Gray(width, height, mm, out);
  }

  /** Sobel gradients; a pixel touching a NaN gets NaN. Returns {gx, gy}. */
  Gray[] gradients() {
    float[] gx = new float[data.length];
    float[] gy = new float[data.length];
    Arrays.fill(gx, Float.NaN);
    Arrays.fill(gy, Float.NaN);
    for (int y = 1; y < height - 1; y++) {
      for (int x = 1; x < width - 1; x++) {
        int i = y * width + x;
        float a = data[i - width - 1];
        float b = data[i - width];
        float c = data[i - width + 1];
        float d = data[i - 1];
        float f = data[i + 1];
        float g = data[i + width - 1];
        float h = data[i + width];
        float k = data[i + width + 1];
        gx[i] = (c + 2 * f + k) - (a + 2 * d + g);
        gy[i] = (g + 2 * h + k) - (a + 2 * b + c);
      }
    }
    return new Gray[] {new Gray(width, height, mm, gx), new Gray(width, height, mm, gy)};
  }

  /** Gradient magnitude, the matching signal: insensitive to each plate's own exposure offset. */
  Gray gradientMagnitude() {
    Gray[] g = gradients();
    float[] out = new float[data.length];
    for (int i = 0; i < out.length; i++) {
      out[i] = (float) Math.hypot(g[0].data[i], g[1].data[i]);
    }
    return new Gray(width, height, mm, out);
  }

  /** Separable area weights from a source axis to a target axis, both starting at 0 mm. */
  private record Weights(int[] first, int[] last, float[][] w) {

    static Weights of(int n, double srcMm, int tn, double mm) {
      int[] first = new int[tn];
      int[] last = new int[tn];
      float[][] w = new float[tn][];
      for (int t = 0; t < tn; t++) {
        double a = t * mm / srcMm;
        double b = Math.min(n, (t + 1) * mm / srcMm);
        int i0 = Math.min(n - 1, (int) Math.floor(a));
        int i1 = Math.max(i0, Math.min(n - 1, (int) Math.ceil(b) - 1));
        float[] ww = new float[i1 - i0 + 1];
        double sum = 0;
        for (int i = i0; i <= i1; i++) {
          double ov = Math.min(i + 1, b) - Math.max(i, a);
          ww[i - i0] = (float) Math.max(0, ov);
          sum += ww[i - i0];
        }
        if (sum <= 0) {
          ww[0] = 1;
          sum = 1;
        }
        for (int i = 0; i < ww.length; i++) {
          ww[i] /= (float) sum;
        }
        first[t] = i0;
        last[t] = i1;
        w[t] = ww;
      }
      return new Weights(first, last, w);
    }

    // a NaN source pixel makes its target NaN: a masked pixel must not leak into a match
    void apply(float[] src, int srcOff, int srcStep, float[] dst, int dstOff, int dstStep) {
      for (int t = 0; t < w.length; t++) {
        double v = 0;
        float[] ww = w[t];
        for (int i = first[t]; i <= last[t]; i++) {
          v += ww[i - first[t]] * src[srcOff + i * srcStep];
        }
        dst[dstOff + t * dstStep] = (float) v;
      }
    }
  }
}
