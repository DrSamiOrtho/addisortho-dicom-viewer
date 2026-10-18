/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

/**
 * Settings of a bone surface build.
 *
 * @param thresholdHu iso value on the CT, in HU
 * @param keepLargestPiece keep only the piece enclosing the largest volume (drops fragments)
 * @param maxTriangles reduce the mesh to at most this many triangles; 0 for no reduction
 * @param spacingMm sample spacing of the working grid, raised when the grid would exceed {@code
 *     maxVoxels}
 * @param growVoxels how far, in working samples, the bone label grows before it masks the CT
 * @param sigmaMm standard deviation of the Gaussian smoothing of the field
 * @param smoothingIterations Taubin iterations on the mesh
 * @param maxVoxels upper bound on the working grid, which costs 5 bytes per sample
 */
public record SurfaceOptions(
    double thresholdHu,
    boolean keepLargestPiece,
    int maxTriangles,
    double spacingMm,
    int growVoxels,
    double sigmaMm,
    int smoothingIterations,
    long maxVoxels) {

  public static final double DEFAULT_THRESHOLD_HU = 200;

  public static final long DEFAULT_MAX_VOXELS = 32_000_000L;

  public SurfaceOptions {
    if (!(spacingMm > 0) || growVoxels < 0 || smoothingIterations < 0 || maxVoxels < 8) {
      throw new IllegalArgumentException("Invalid surface options");
    }
  }

  /** The reference chain: 0.6 mm, label grown by 2 samples, 200 HU, 15 smoothing iterations. */
  public static SurfaceOptions defaults() {
    return new SurfaceOptions(DEFAULT_THRESHOLD_HU, true, 0, 0.6, 2, 0.6, 15, DEFAULT_MAX_VOXELS);
  }

  public SurfaceOptions withThreshold(double hu) {
    return new SurfaceOptions(
        hu,
        keepLargestPiece,
        maxTriangles,
        spacingMm,
        growVoxels,
        sigmaMm,
        smoothingIterations,
        maxVoxels);
  }

  public SurfaceOptions withKeepLargestPiece(boolean keep) {
    return new SurfaceOptions(
        thresholdHu,
        keep,
        maxTriangles,
        spacingMm,
        growVoxels,
        sigmaMm,
        smoothingIterations,
        maxVoxels);
  }

  public SurfaceOptions withMaxTriangles(int max) {
    return new SurfaceOptions(
        thresholdHu,
        keepLargestPiece,
        max,
        spacingMm,
        growVoxels,
        sigmaMm,
        smoothingIterations,
        maxVoxels);
  }

  public SurfaceOptions withMaxVoxels(long max) {
    return new SurfaceOptions(
        thresholdHu,
        keepLargestPiece,
        maxTriangles,
        spacingMm,
        growVoxels,
        sigmaMm,
        smoothingIterations,
        max);
  }
}
