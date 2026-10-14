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
 * The opacity the renderer gives a voxel value, on the CPU: the linear window/level of {@code
 * voxelFunctions.glsl} followed by the alpha of the preset's colour map sampled with linear
 * filtering, times the opacity factor. The non-linear LUT shapes are approximated by the linear
 * one.
 */
public final class TransferFunction {

  private final float[] alpha;
  private final double windowWidth;
  private final double windowCenter;
  private final double opacityFactor;

  /**
   * @param lutRgba the preset colour map, RGBA bytes, one entry per intensity step from the
   *     preset's lowest intensity
   * @param windowWidth window width applied to the voxel values
   * @param windowCenter window centre applied to the voxel values
   * @param opacityFactor the renderer's opacity factor
   */
  public TransferFunction(
      byte[] lutRgba, double windowWidth, double windowCenter, double opacityFactor) {
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
    double slope = width / windowWidth;
    double intercept = width - slope * (windowCenter + windowWidth / 2.0);
    double windowed = Math.clamp(value * slope + intercept, 0.0, width);
    // texture(colorMap, vec2(n, 0)) with GL_LINEAR: texel centres at (i + 0.5) / width.
    double pos = windowed - 0.5;
    int i0 = (int) Math.floor(pos);
    double f = pos - i0;
    float a0 = alpha[Math.clamp(i0, 0, alpha.length - 1)];
    float a1 = alpha[Math.clamp(i0 + 1, 0, alpha.length - 1)];
    double a = a0 * (1 - f) + a1 * f;
    return Math.min(a * opacityFactor, 1.0);
  }

  /**
   * The lowest voxel value the renderer shows, i.e. whose opacity reaches {@code minOpacity}, or
   * {@link Double#NaN} when nothing is visible.
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
