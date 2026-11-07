/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.mesh;

/**
 * A scalar field sampled on a regular grid whose axes follow the patient axes (DICOM LPS). Sample
 * {@code (i, j, k)} sits at {@code origin + (i * spacingX, j * spacingY, k * spacingZ)} in mm.
 */
public final class ScalarGrid {

  private final int sizeX;
  private final int sizeY;
  private final int sizeZ;
  private final double[] origin;
  private final double[] spacing;
  private final float[] values;

  /**
   * @param origin position of sample (0, 0, 0) in mm
   * @param spacing distance between samples along x, y and z in mm
   */
  public ScalarGrid(int sizeX, int sizeY, int sizeZ, double[] origin, double[] spacing) {
    if (sizeX < 1 || sizeY < 1 || sizeZ < 1) {
      throw new IllegalArgumentException("Empty grid");
    }
    long count = (long) sizeX * sizeY * sizeZ;
    if (count > Integer.MAX_VALUE - 8) {
      throw new IllegalArgumentException("Grid too large: " + count + " samples");
    }
    for (double s : spacing) {
      if (!(s > 0)) {
        throw new IllegalArgumentException("Spacing must be positive");
      }
    }
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.origin = origin.clone();
    this.spacing = spacing.clone();
    this.values = new float[(int) count];
  }

  public int sizeX() {
    return sizeX;
  }

  public int sizeY() {
    return sizeY;
  }

  public int sizeZ() {
    return sizeZ;
  }

  public double originX() {
    return origin[0];
  }

  public double originY() {
    return origin[1];
  }

  public double originZ() {
    return origin[2];
  }

  public double spacingX() {
    return spacing[0];
  }

  public double spacingY() {
    return spacing[1];
  }

  public double spacingZ() {
    return spacing[2];
  }

  public int index(int i, int j, int k) {
    return (k * sizeY + j) * sizeX + i;
  }

  public float get(int i, int j, int k) {
    return values[index(i, j, k)];
  }

  public void set(int i, int j, int k, float value) {
    values[index(i, j, k)] = value;
  }

  /** Position of sample {@code i} along x in mm. */
  public double x(double i) {
    return origin[0] + i * spacing[0];
  }

  public double y(double j) {
    return origin[1] + j * spacing[1];
  }

  public double z(double k) {
    return origin[2] + k * spacing[2];
  }

  /** The samples in x-fastest order; shared with the grid, not copied. */
  float[] values() {
    return values;
  }
}
