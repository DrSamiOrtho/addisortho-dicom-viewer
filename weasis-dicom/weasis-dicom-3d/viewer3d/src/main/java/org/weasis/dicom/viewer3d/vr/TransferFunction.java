/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer3d.vr;

import java.util.Arrays;

/**
 * The opacity the renderer gives a voxel value, on the CPU: the window/level of {@code
 * voxelFunctions.glsl} in the selected LUT shape (linear, sigmoid, normalised sigmoid, log, inverse
 * log, as {@code getWindowLevel} applies them), followed by the alpha of the preset's colour map
 * sampled with linear filtering, times the opacity factor.
 */
public final class TransferFunction {

  /** The LUT shapes, numbered as {@code RenderingLayer.getLutShapeId()} and the shaders do. */
  public static final int LINEAR = 0;

  public static final int SIGMOID = 1;
  public static final int SIGMOID_NORM = 2;
  public static final int LOG = 3;
  public static final int LOG_INV = 4;

  private final float[] alpha;
  private final double windowWidth;
  private final double windowCenter;
  private final double opacityFactor;
  private final int lutShape;

  /**
   * @param lutRgba the preset colour map, RGBA bytes, one entry per intensity step from the
   *     preset's lowest intensity
   * @param windowWidth window width applied to the voxel values
   * @param windowCenter window centre applied to the voxel values
   * @param opacityFactor the renderer's opacity factor
   */
  public TransferFunction(
      byte[] lutRgba, double windowWidth, double windowCenter, double opacityFactor) {
    this(lutRgba, windowWidth, windowCenter, opacityFactor, LINEAR);
  }

  /**
   * @param lutShape one of {@link #LINEAR}, {@link #SIGMOID}, {@link #SIGMOID_NORM}, {@link #LOG},
   *     {@link #LOG_INV}
   */
  public TransferFunction(
      byte[] lutRgba, double windowWidth, double windowCenter, double opacityFactor, int lutShape) {
    int width = lutRgba.length / 4;
    if (width <= 0) {
      throw new IllegalArgumentException("Empty colour map");
    }
    this.alpha = new float[width];
    for (int i = 0; i < width; i++) {
      alpha[i] = (lutRgba[i * 4 + 3] & 0xFF) / 255f;
    }
    this.windowWidth = windowWidth <= 0 ? 1 : windowWidth;
    this.windowCenter = windowCenter;
    this.opacityFactor = opacityFactor;
    this.lutShape = lutShape;
  }

  public int lutShape() {
    return lutShape;
  }

  /** A step function: fully opaque from {@code threshold} on, transparent below. */
  public static TransferFunction step(double threshold, double lutMin, int width) {
    byte[] lut = new byte[width * 4];
    for (int i = 0; i < width; i++) {
      lut[i * 4 + 3] = (byte) (lutMin + i >= threshold ? 0xFF : 0);
    }
    return new TransferFunction(lut, width, lutMin + width / 2.0, 1.0);
  }

  public int width() {
    return alpha.length;
  }

  /** Opacity, in [0, 1], of a voxel value as the preset and window/level map it. */
  public double opacity(double value) {
    double width = alpha.length;
    double windowed = windowed(value);
    // texture(colorMap, vec2(n, 0)) with GL_LINEAR: texel centres at (i + 0.5) / width.
    double pos = windowed - 0.5;
    int i0 = (int) Math.floor(pos);
    double f = pos - i0;
    float a0 = alpha[Math.clamp(i0, 0, alpha.length - 1)];
    float a1 = alpha[Math.clamp(i0 + 1, 0, alpha.length - 1)];
    double a = a0 * (1 - f) + a1 * f;
    return Math.min(a * opacityFactor, 1.0);
  }

  /** The windowed value in [0, width], the shaders' {@code getWindowLevel} for the LUT shape. */
  public double windowed(double value) {
    double width = alpha.length;
    double result =
        switch (lutShape) {
          case SIGMOID -> shaped(value, false, SIGMOID);
          case SIGMOID_NORM -> shaped(value, true, SIGMOID);
          case LOG -> shaped(value, true, LOG);
          case LOG_INV -> shaped(value, true, LOG_INV);
          default -> {
            double slope = width / windowWidth;
            double intercept = width - slope * (windowCenter + windowWidth / 2.0);
            yield value * slope + intercept;
          }
        };
    // A log of a negative argument is undefined in the shader; a clamp of NaN shows nothing.
    return Double.isNaN(result) ? 0.0 : Math.clamp(result, 0.0, width);
  }

  /** {@code getWindowLevelFunc} of voxelFunctions.glsl, with its DICOM default factors. */
  private double shaped(double value, boolean normalize, int type) {
    double nFactor = type == SIGMOID ? -20 : 20;
    double outRange = alpha.length;
    double val = curve(value, nFactor, outRange, type);
    if (normalize) {
      double lowLevel = windowCenter - windowWidth / 2.0;
      double highLevel = windowCenter + windowWidth / 2.0;
      double minValue = curve(lowLevel, nFactor, outRange, type);
      double maxValue = curve(highLevel, nFactor, outRange, type);
      val = (val - minValue) * outRange / Math.abs(maxValue - minValue);
    }
    return val;
  }

  private double curve(double value, double nFactor, double outRange, int type) {
    double x = (value - windowCenter) / windowWidth;
    return switch (type) {
      case SIGMOID -> outRange / (1 + Math.exp((2 * nFactor / 10.0) * x));
      case LOG_INV -> outRange * Math.exp((nFactor / 10.0) * x);
      case LOG -> outRange * Math.log((nFactor / 10.0) * (1 + x));
      default -> 0;
    };
  }

  /**
   * The lowest voxel value the renderer shows with a linear window, i.e. whose opacity reaches
   * {@code minOpacity}, or {@link Double#NaN} when nothing is visible. For the logs and the
   * harness; the selection itself tests each voxel's opacity.
   */
  public double visibleThreshold(double minOpacity) {
    int first = -1;
    for (int i = 0; i < alpha.length; i++) {
      if (alpha[i] * opacityFactor >= minOpacity) {
        first = i;
        break;
      }
    }
    if (first < 0) {
      return Double.NaN;
    }
    // Invert the window: the voxel value whose windowed value is the texel centre of entry first.
    double width = alpha.length;
    double slope = width / windowWidth;
    double intercept = width - slope * (windowCenter + windowWidth / 2.0);
    return (first + 0.5 - intercept) / slope;
  }

  @Override
  public String toString() {
    return "TransferFunction" + Arrays.toString(Arrays.copyOf(alpha, Math.min(8, alpha.length)));
  }
}
