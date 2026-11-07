/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * A synthetic CT and segmentation on one voxel grid aligned with LPS, sampled the way the viewer
 * samples real ones: trilinear CT values, nearest-voxel labels.
 */
final class SyntheticScan {

  private final int[] size;
  private final double[] origin;
  private final double[] spacing;
  private final float[] hu;
  private final byte[] labels;

  /**
   * @param huAt CT value at a voxel centre, given as LPS x, y, z
   * @param labelAt label at a voxel centre
   */
  SyntheticScan(
      int[] size,
      double[] origin,
      double[] spacing,
      ToDoubleFunction<double[]> huAt,
      ToIntFunction<double[]> labelAt) {
    this.size = size;
    this.origin = origin;
    this.spacing = spacing;
    int count = size[0] * size[1] * size[2];
    this.hu = new float[count];
    this.labels = new byte[count];
    double[] p = new double[3];
    for (int k = 0; k < size[2]; k++) {
      for (int j = 0; j < size[1]; j++) {
        for (int i = 0; i < size[0]; i++) {
          p[0] = origin[0] + i * spacing[0];
          p[1] = origin[1] + j * spacing[1];
          p[2] = origin[2] + k * spacing[2];
          int index = index(i, j, k);
          hu[index] = (float) huAt.applyAsDouble(p);
          labels[index] = (byte) labelAt.applyAsInt(p);
        }
      }
    }
  }

  private int index(int i, int j, int k) {
    return (k * size[1] + j) * size[0] + i;
  }

  HuSampler ct() {
    return (x, y, z) -> {
      double fi = (x - origin[0]) / spacing[0];
      double fj = (y - origin[1]) / spacing[1];
      double fk = (z - origin[2]) / spacing[2];
      if (fi < 0 || fj < 0 || fk < 0 || fi > size[0] - 1 || fj > size[1] - 1 || fk > size[2] - 1) {
        return Double.NaN;
      }
      int i = Math.min((int) fi, size[0] - 2);
      int j = Math.min((int) fj, size[1] - 2);
      int k = Math.min((int) fk, size[2] - 2);
      double tx = fi - i;
      double ty = fj - j;
      double tz = fk - k;
      double sum = 0;
      for (int c = 0; c < 8; c++) {
        int dx = c & 1;
        int dy = (c >> 1) & 1;
        int dz = (c >> 2) & 1;
        double w = (dx == 0 ? 1 - tx : tx) * (dy == 0 ? 1 - ty : ty) * (dz == 0 ? 1 - tz : tz);
        sum += w * hu[index(i + dx, j + dy, k + dz)];
      }
      return sum;
    };
  }

  LabelSampler labels() {
    return (x, y, z) -> {
      int i = (int) Math.round((x - origin[0]) / spacing[0]);
      int j = (int) Math.round((y - origin[1]) / spacing[1]);
      int k = (int) Math.round((z - origin[2]) / spacing[2]);
      if (i < 0 || j < 0 || k < 0 || i >= size[0] || j >= size[1] || k >= size[2]) {
        return LabelSampler.NONE;
      }
      return labels[index(i, j, k)];
    };
  }

  /** Box around the voxels labelled {@code label}, half a voxel beyond their centres. */
  Box boxOf(int label) {
    double[] b = {
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    for (int k = 0; k < size[2]; k++) {
      for (int j = 0; j < size[1]; j++) {
        for (int i = 0; i < size[0]; i++) {
          if (labels[index(i, j, k)] == label) {
            int[] ijk = {i, j, k};
            for (int axis = 0; axis < 3; axis++) {
              double c = origin[axis] + ijk[axis] * spacing[axis];
              b[axis] = Math.min(b[axis], c - spacing[axis] / 2);
              b[axis + 3] = Math.max(b[axis + 3], c + spacing[axis] / 2);
            }
          }
        }
      }
    }
    return new Box(b[0], b[1], b[2], b[3], b[4], b[5]);
  }
}
