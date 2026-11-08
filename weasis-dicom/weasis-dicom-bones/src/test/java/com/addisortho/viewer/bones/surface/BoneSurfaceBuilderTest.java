/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.mesh.MeshChecks;
import com.addisortho.viewer.bones.mesh.TriangleMesh;
import com.addisortho.viewer.bones.surface.BoneSurfaceBuilder.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoneSurfaceBuilderTest {

  private static final double RADIUS = 20;

  /** Centre off the voxel grid, far from the LPS origin like a real knee. */
  private static final double[] CENTRE = {-71.37, 18.62, -903.41};

  /**
   * CT of a ball whose edge crosses 200 HU at the radius, with partial-volume blur over a few mm:
   * 400 HU per mm, clamped to air and dense cortical bone.
   */
  private static double ballHu(double distance) {
    return Math.clamp(200 + 400 * (RADIUS - distance), -1000, 1600);
  }

  private static double distance(double[] p, double[] centre) {
    double dx = p[0] - centre[0];
    double dy = p[1] - centre[1];
    double dz = p[2] - centre[2];
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /** A ball segmented on the given voxel grid, the scan covering it with 8 mm to spare. */
  private static SyntheticScan ballScan(double sx, double sy, double sz) {
    double[] spacing = {sx, sy, sz};
    int[] size = new int[3];
    double[] origin = new double[3];
    for (int axis = 0; axis < 3; axis++) {
      size[axis] = (int) Math.ceil(2 * (RADIUS + 8) / spacing[axis]);
      origin[axis] = CENTRE[axis] - (RADIUS + 8) + 0.37 * spacing[axis];
    }
    return new SyntheticScan(
        size,
        origin,
        spacing,
        p -> ballHu(distance(p, CENTRE)),
        p -> distance(p, CENTRE) <= RADIUS ? LabelSampler.BONE : LabelSampler.NONE);
  }

  private static Result build(SyntheticScan scan, HuSampler ct, SurfaceOptions options) {
    return BoneSurfaceBuilder.build(
        scan.labels(), scan.boxOf(LabelSampler.BONE), ct, options, Progress.NONE);
  }

  private static void assertBall(TriangleMesh mesh, double volumeTolerance, double[] centre) {
    MeshChecks.assertClosedManifold(mesh);
    double expected = MeshChecks.sphereVolume(RADIUS);
    double volume = mesh.signedVolume();
    assertEquals(expected, volume, volumeTolerance * expected, "volume");
    double[] c = mesh.centroid();
    assertTrue(distance(c, centre) < 0.5, "centroid off by " + distance(c, centre) + " mm");
  }

  @ParameterizedTest
  @CsvSource({"1.0, 1.0, 1.0", "0.7, 0.7, 2.5"})
  void ballFromCtIsClosedAccurateAndInPlace(double sx, double sy, double sz) {
    SyntheticScan scan = ballScan(sx, sy, sz);

    Result result = build(scan, scan.ct(), SurfaceOptions.defaults());

    assertTrue(result.fromCt());
    assertEquals(200, result.iso());
    assertEquals(0.6, result.spacingMm(), 1e-9);
    assertEquals(1, result.piecesKept());
    assertBall(result.mesh(), 0.02, CENTRE);
  }

  @Test
  void labelAloneGivesTheLabelSurface() {
    SyntheticScan scan = ballScan(1, 1, 1);

    Result result = build(scan, null, SurfaceOptions.defaults());

    assertTrue(!result.fromCt());
    assertEquals(0.5, result.iso());
    // The voxel blocks of the label stand for the ball, so the volume is only close.
    assertBall(result.mesh(), 0.04, CENTRE);
  }

  @Test
  void thresholdMovesTheSurface() {
    SyntheticScan scan = ballScan(1, 1, 1);

    // 600 HU is reached 1 mm inside the edge (400 HU per mm).
    Result result = build(scan, scan.ct(), SurfaceOptions.defaults().withThreshold(600));

    double expected = MeshChecks.sphereVolume(RADIUS - 1);
    assertEquals(expected, result.mesh().signedVolume(), 0.02 * expected);
  }

  @Test
  void spacingGrowsToFitTheVoxelBudget() {
    SyntheticScan scan = ballScan(1, 1, 1);

    Result result = build(scan, scan.ct(), SurfaceOptions.defaults().withMaxVoxels(100_000));

    assertTrue(result.spacingMm() > 0.6, "spacing " + result.spacingMm());
    assertBall(result.mesh(), 0.05, CENTRE);
  }

  @Test
  void reducesTheTriangleCount() {
    SyntheticScan scan = ballScan(1, 1, 1);

    Result result = build(scan, scan.ct(), SurfaceOptions.defaults().withMaxTriangles(20_000));

    assertTrue(result.mesh().triangleCount() <= 20_000);
    double expected = MeshChecks.sphereVolume(RADIUS);
    assertEquals(expected, result.mesh().signedVolume(), 0.05 * expected);
  }

  /** Two fragments of one bone label: a ball of 12 mm and one of 6 mm, 40 mm apart. */
  private static SyntheticScan twoFragments() {
    double[] big = {10, 20, -300};
    double[] small = {50, 20, -300};
    return new SyntheticScan(
        new int[] {80, 44, 44},
        new double[] {-10, -2, -322},
        new double[] {1, 1, 1},
        p ->
            Math.clamp(
                200 + 400 * Math.max(12 - distance(p, big), 6 - distance(p, small)), -1000, 1600),
        p ->
            distance(p, big) <= 12 || distance(p, small) <= 6
                ? LabelSampler.BONE
                : LabelSampler.NONE);
  }

  @Test
  void largestPieceOnlyDropsTheSmallFragment() {
    SyntheticScan scan = twoFragments();

    Result result = build(scan, scan.ct(), SurfaceOptions.defaults());

    assertEquals(2, result.piecesFound());
    assertEquals(1, result.piecesKept());
    MeshChecks.assertClosedManifold(result.mesh());
    double expected = MeshChecks.sphereVolume(12);
    assertEquals(expected, result.mesh().signedVolume(), 0.02 * expected);
    assertTrue(result.mesh().bounds()[3] < 30, "the small fragment at x = 50 must be gone");
  }

  @Test
  void allPiecesKeepsBothFragments() {
    SyntheticScan scan = twoFragments();

    Result result = build(scan, scan.ct(), SurfaceOptions.defaults().withKeepLargestPiece(false));

    assertEquals(2, result.piecesFound());
    assertEquals(2, result.piecesKept());
    MeshChecks.assertClosedManifold(result.mesh());
    double expected = MeshChecks.sphereVolume(12) + MeshChecks.sphereVolume(6);
    assertEquals(expected, result.mesh().signedVolume(), 0.02 * expected);
  }

  @Test
  void nothingAboveTheThresholdGivesAnEmptyMesh() {
    SyntheticScan scan = ballScan(1, 1, 1);

    Result result = build(scan, scan.ct(), SurfaceOptions.defaults().withThreshold(5000));

    assertTrue(result.mesh().isEmpty());
    assertEquals(0, result.piecesFound());
  }
}
