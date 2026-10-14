/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.mesh.MeshChecks;
import com.addisortho.viewer.bones.mesh.TriangleMesh;
import com.addisortho.viewer.bones.surface.BoneSurfaceBuilder;
import com.addisortho.viewer.bones.surface.Box;
import com.addisortho.viewer.bones.surface.LabelSampler;
import com.addisortho.viewer.bones.surface.Progress;
import com.addisortho.viewer.bones.surface.SurfaceOptions;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.codec.seg.SegmentationVolume;
import org.weasis.opencv.seg.RegionAttributes;

class SegmentLabelsTest {

  private static final int FEMUR = 1;
  private static final int TIBIA = 2;
  private static final int PATELLA = 3;
  private static final int EMPTY = 4;

  /**
   * A synthetic SEG on an oblique grid (rotated 30 degrees about z, 0.8 x 0.8 x 1.5 mm) far from
   * the LPS origin: femur x 4-9, tibia x 10-15 (touching), both y 5-12 and z 3-8; the patella
   * overlaps the femur at x 8-9, y 5-6, z 3.
   */
  private static SegmentationVolume knee() {
    Map<Integer, RegionAttributes> segments = new LinkedHashMap<>();
    segments.put(FEMUR, new RegionAttributes(FEMUR, "Femur", Color.RED));
    segments.put(TIBIA, new RegionAttributes(TIBIA, "Tibia", Color.GREEN));
    segments.put(PATELLA, new RegionAttributes(PATELLA, "Patella", Color.BLUE));
    segments.put(EMPTY, new RegionAttributes(EMPTY, "Fibula", Color.YELLOW));
    double a = Math.toRadians(30);
    SegmentationVolume v =
        new SegmentationVolume(
            24,
            20,
            16,
            new Vector3d(0.8, 0.8, 1.5),
            new Vector3d(-120, 40, -700),
            new Vector3d(Math.cos(a), Math.sin(a), 0),
            new Vector3d(-Math.sin(a), Math.cos(a), 0),
            new Vector3d(0, 0, 1),
            segments);
    for (int z = 3; z <= 8; z++) {
      for (int y = 5; y <= 12; y++) {
        for (int x = 4; x <= 15; x++) {
          v.addLabel(x, y, z, x <= 9 ? FEMUR : TIBIA);
        }
      }
    }
    for (int y = 5; y <= 6; y++) {
      for (int x = 8; x <= 9; x++) {
        v.addLabel(x, y, 3, PATELLA);
      }
    }
    return v;
  }

  private static SegmentLabels extract(SegmentationVolume v, int segment, double marginMm) {
    return SegmentLabels.extract(v, Set.of(FEMUR, TIBIA, PATELLA, EMPTY), segment, marginMm)
        .orElseThrow();
  }

  private static int labelAtVoxel(
      SegmentLabels labels, SegmentationVolume v, double x, double y, double z) {
    // Fractional voxel positions, through the volume's own voxel to LPS mapping.
    Vector3d o = v.voxelToLps(0, 0, 0);
    Vector3d ex = v.voxelToLps(1, 0, 0).sub(o);
    Vector3d ey = v.voxelToLps(0, 1, 0).sub(o);
    Vector3d ez = v.voxelToLps(0, 0, 1).sub(o);
    Vector3d p = new Vector3d(o).fma(x, ex).fma(y, ey).fma(z, ez);
    return labels.labelAt(p.x, p.y, p.z);
  }

  @Test
  void boneAndNeighboursComeOutOfTheObliqueGrid() {
    SegmentationVolume v = knee();

    SegmentLabels femur = extract(v, FEMUR, 3);

    assertEquals(LabelSampler.BONE, labelAtVoxel(femur, v, 5, 6, 4));
    assertEquals(LabelSampler.BONE, labelAtVoxel(femur, v, 5.4, 6.4, 4.4));
    assertEquals(LabelSampler.OTHER, labelAtVoxel(femur, v, 12, 6, 4));
    // Overlap with the patella: the exported bone wins.
    assertEquals(LabelSampler.BONE, labelAtVoxel(femur, v, 8, 5, 3));
    assertEquals(LabelSampler.NONE, labelAtVoxel(femur, v, 2, 6, 4));
    assertEquals(LabelSampler.NONE, labelAtVoxel(femur, v, 5, 6, 10));
  }

  @Test
  void overlappingSegmentIsAnotherSegmentForTheNeighbour() {
    SegmentationVolume v = knee();

    SegmentLabels tibia = extract(v, TIBIA, 3);

    assertEquals(LabelSampler.BONE, labelAtVoxel(tibia, v, 12, 6, 4));
    assertEquals(LabelSampler.OTHER, labelAtVoxel(tibia, v, 9, 6, 4));
    assertEquals(LabelSampler.OTHER, labelAtVoxel(tibia, v, 8, 5, 3));
  }

  @Test
  void onlyTheBoxAroundTheBoneIsKept() {
    SegmentationVolume v = knee();

    // 3 mm is 4 voxels along x: the copy reaches x = 13 and stops there.
    SegmentLabels femur = extract(v, FEMUR, 3);
    SegmentLabels tight = extract(v, FEMUR, 0);

    assertEquals(LabelSampler.OTHER, labelAtVoxel(femur, v, 13, 6, 4));
    assertEquals(LabelSampler.NONE, labelAtVoxel(femur, v, 14, 6, 4));
    assertEquals(LabelSampler.NONE, labelAtVoxel(tight, v, 10, 6, 4));
  }

  @Test
  void boxHoldsEveryBoneVoxel() {
    SegmentationVolume v = knee();

    Box box = extract(v, FEMUR, 3).boneBox();

    for (int z = 3; z <= 8; z++) {
      for (int y = 5; y <= 12; y++) {
        for (int x = 4; x <= 9; x++) {
          Vector3d p = v.voxelToLps(x, y, z);
          assertTrue(
              p.x > box.minX() && p.x < box.maxX() && p.y > box.minY() && p.y < box.maxY(),
              "voxel " + x + ", " + y + ", " + z);
          assertTrue(p.z > box.minZ() && p.z < box.maxZ());
        }
      }
    }
    // Six slices of 1.5 mm.
    assertEquals(9, box.maxZ() - box.minZ(), 1e-9);
  }

  @Test
  void labelledBallComesOutAtItsPatientPosition() {
    // A ball of 12 mm segmented on an oblique 1 x 1 x 2 mm grid, without CT.
    double[] centre = {-95.3, 61.8, -671.6};
    double radius = 12;
    double a = Math.toRadians(30);
    SegmentationVolume v =
        new SegmentationVolume(
            50,
            50,
            24,
            new Vector3d(1, 1, 2),
            new Vector3d(-110, 30, -695),
            new Vector3d(Math.cos(a), Math.sin(a), 0),
            new Vector3d(-Math.sin(a), Math.cos(a), 0),
            new Vector3d(0, 0, 1),
            Map.of(FEMUR, new RegionAttributes(FEMUR, "Femur", Color.RED)));
    for (int z = 0; z < 24; z++) {
      for (int y = 0; y < 50; y++) {
        for (int x = 0; x < 50; x++) {
          if (v.voxelToLps(x, y, z).distance(centre[0], centre[1], centre[2]) <= radius) {
            v.addLabel(x, y, z, FEMUR);
          }
        }
      }
    }
    SegmentLabels labels = SegmentLabels.extract(v, Set.of(FEMUR), FEMUR, 12).orElseThrow();

    TriangleMesh mesh =
        BoneSurfaceBuilder.build(
                labels, labels.boneBox(), null, SurfaceOptions.defaults(), Progress.NONE)
            .mesh();

    MeshChecks.assertClosedManifold(mesh);
    double[] c = mesh.centroid();
    assertEquals(0, new Vector3d(c).distance(centre[0], centre[1], centre[2]), 0.5);
    double expected = MeshChecks.sphereVolume(radius);
    assertEquals(expected, mesh.signedVolume(), 0.06 * expected);
  }

  @Test
  void emptySegmentGivesNothing() {
    SegmentationVolume v = knee();

    Optional<SegmentLabels> none =
        SegmentLabels.extract(v, Set.of(FEMUR, TIBIA, PATELLA, EMPTY), EMPTY, 3);

    assertTrue(none.isEmpty());
  }
}
