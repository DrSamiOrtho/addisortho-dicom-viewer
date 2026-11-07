/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/**
 * Settings of a region grow.
 *
 * @param lowerHu lowest CT value taken into the region, in HU
 * @param upperHu highest CT value taken into the region, in HU
 * @param closingRadius radius of the morphological closing, in 6-connected voxel steps; 0 for none
 * @param separationVoxels erosion depth that splits the seeded bone from a neighbour joined to it
 *     by thin bridges; 0 for none
 * @param fillHoles fill the holes of every slice, so the marrow is inside the bone
 * @param minPieceVoxels pieces smaller than this are dropped, except the seeded one
 * @param seedSearchVoxels when the seed is outside the HU range, the nearest voxel in range within
 *     this many voxels is taken instead
 */
public record GrowOptions(
    double lowerHu,
    double upperHu,
    int closingRadius,
    int separationVoxels,
    boolean fillHoles,
    long minPieceVoxels,
    int seedSearchVoxels) {

  public static final double DEFAULT_LOWER_HU = 200;
  public static final double DEFAULT_UPPER_HU = 3000;

  /** Upper bound of the morphology radii, beyond which the steps take long and mean little. */
  public static final int MAX_RADIUS = 10;

  public GrowOptions {
    if (!(lowerHu <= upperHu)
        || closingRadius < 0
        || closingRadius > MAX_RADIUS
        || separationVoxels < 0
        || separationVoxels > MAX_RADIUS
        || minPieceVoxels < 0
        || seedSearchVoxels < 0
        || seedSearchVoxels > MAX_RADIUS) {
      throw new IllegalArgumentException("Invalid region grow options");
    }
  }

  /**
   * 200 to 3000 HU, closing of 1 voxel, holes filled, no separation, seed searched 3 voxels away.
   */
  public static GrowOptions defaults() {
    return new GrowOptions(DEFAULT_LOWER_HU, DEFAULT_UPPER_HU, 1, 0, true, 0, 3);
  }

  public GrowOptions withRange(double lower, double upper) {
    return new GrowOptions(
        lower, upper, closingRadius, separationVoxels, fillHoles, minPieceVoxels, seedSearchVoxels);
  }

  public GrowOptions withClosingRadius(int radius) {
    return new GrowOptions(
        lowerHu, upperHu, radius, separationVoxels, fillHoles, minPieceVoxels, seedSearchVoxels);
  }

  public GrowOptions withSeparation(int voxels) {
    return new GrowOptions(
        lowerHu, upperHu, closingRadius, voxels, fillHoles, minPieceVoxels, seedSearchVoxels);
  }

  public GrowOptions withFillHoles(boolean fill) {
    return new GrowOptions(
        lowerHu, upperHu, closingRadius, separationVoxels, fill, minPieceVoxels, seedSearchVoxels);
  }

  public GrowOptions withMinPieceVoxels(long voxels) {
    return new GrowOptions(
        lowerHu, upperHu, closingRadius, separationVoxels, fillHoles, voxels, seedSearchVoxels);
  }

  public GrowOptions withSeedSearch(int voxels) {
    return new GrowOptions(
        lowerHu, upperHu, closingRadius, separationVoxels, fillHoles, minPieceVoxels, voxels);
  }

  boolean inRange(double hu) {
    return hu >= lowerHu && hu <= upperHu;
  }
}
