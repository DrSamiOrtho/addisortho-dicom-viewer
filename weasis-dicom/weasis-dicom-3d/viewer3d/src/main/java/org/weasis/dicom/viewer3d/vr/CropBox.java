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

/**
 * Axis-aligned crop box in normalised volume-texture coordinates, {@code [0, 1]} on each axis. The
 * bounds are always valid: clamped to the volume, ordered, and at least {@link #MIN_EXTENT} apart,
 * so the box never collapses to nothing.
 *
 * <p>The sides follow the convention of the MPR crosshair cut ({@link CrosshairCutMode}): texture X
 * runs from right to left, Y from front to back and Z from down to up.
 */
public record CropBox(
    double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {

  /** Smallest extent left on each axis, as a fraction of the volume. */
  public static final double MIN_EXTENT = 0.01;

  /** The whole volume, which crops nothing. */
  public static final CropBox FULL = new CropBox(0, 1, 0, 1, 0, 1);

  /** One face of the box, named by the side of the volume it cuts away. */
  public enum Side {
    RIGHT(0, false),
    LEFT(0, true),
    FRONT(1, false),
    BACK(1, true),
    DOWN(2, false),
    UP(2, true);

    private final int axis;
    private final boolean upper;

    Side(int axis, boolean upper) {
      this.axis = axis;
      this.upper = upper;
    }
  }

  public CropBox {
    double[] x = normalize(minX, maxX);
    double[] y = normalize(minY, maxY);
    double[] z = normalize(minZ, maxZ);
    minX = x[0];
    maxX = x[1];
    minY = y[0];
    maxY = y[1];
    minZ = z[0];
    maxZ = z[1];
  }

  /** Clamps to [0, 1], orders, then widens around the centre to {@link #MIN_EXTENT} if needed. */
  private static double[] normalize(double lo, double hi) {
    double a = clamp01(lo, 0.0);
    double b = clamp01(hi, 1.0);
    if (a > b) {
      double t = a;
      a = b;
      b = t;
    }
    if (b - a < MIN_EXTENT) {
      double centre = Math.clamp((a + b) / 2.0, MIN_EXTENT / 2.0, 1.0 - MIN_EXTENT / 2.0);
      a = centre - MIN_EXTENT / 2.0;
      b = centre + MIN_EXTENT / 2.0;
    }
    return new double[] {a, b};
  }

  private static double clamp01(double value, double fallback) {
    return Double.isNaN(value) ? fallback : Math.clamp(value, 0.0, 1.0);
  }

  /** Returns the fraction of the volume cut away on {@code side}, in [0, 1]. */
  public double cut(Side side) {
    double bound = bound(side.axis, side.upper);
    return side.upper ? 1.0 - bound : bound;
  }

  /**
   * Returns a copy cutting {@code fraction} of the volume away on {@code side}. The fraction is
   * clamped so that the opposite side keeps at least {@link #MIN_EXTENT}; the opposite face never
   * moves.
   */
  public CropBox withCut(Side side, double fraction) {
    double f = Double.isNaN(fraction) ? 0.0 : fraction;
    double[] b = {minX, maxX, minY, maxY, minZ, maxZ};
    int lo = side.axis * 2;
    if (side.upper) {
      b[lo + 1] = Math.max(Math.min(1.0 - f, 1.0), b[lo] + MIN_EXTENT);
    } else {
      b[lo] = Math.min(Math.max(f, 0.0), b[lo + 1] - MIN_EXTENT);
    }
    return new CropBox(b[0], b[1], b[2], b[3], b[4], b[5]);
  }

  /** {@code true} when nothing is cut away. */
  public boolean isFull() {
    return equals(FULL);
  }

  private double bound(int axis, boolean upper) {
    return switch (axis) {
      case 0 -> upper ? maxX : minX;
      case 1 -> upper ? maxY : minY;
      default -> upper ? maxZ : minZ;
    };
  }
}
