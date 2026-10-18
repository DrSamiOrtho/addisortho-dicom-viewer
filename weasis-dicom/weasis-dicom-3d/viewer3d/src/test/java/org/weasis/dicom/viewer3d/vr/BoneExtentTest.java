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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

class BoneExtentTest {

  private static final Vector3i SIZE = new Vector3i(100, 100, 60);
  private static final Vector3d MM = new Vector3d(1, 1, 1);

  /**
   * A synthetic leg scan, 1 mm voxels: air, a soft-tissue cylinder, two bone cylinders (both legs),
   * the scanner table as a thin plate behind them, and one bright speck far away.
   */
  private static VoxelSampler legs(boolean withTable, boolean withSpeck) {
    return (x, y, z) -> {
      if (x < 0 || y < 0 || z < 0 || x >= SIZE.x || y >= SIZE.y || z >= SIZE.z) {
        return Double.NaN;
      }
      if (withSpeck && x == 2 && y == 2 && z == 2) {
        return 900;
      }
      if (withTable && y >= 88 && y <= 90 && x >= 5 && x <= 95) {
        return 400;
      }
      boolean inZ = z >= 10 && z <= 50;
      if (inZ && (sq(x - 35) + sq(y - 45) <= 64 || sq(x - 65) + sq(y - 45) <= 64)) {
        return 700;
      }
      if (sq(x - 50) + sq(y - 45) <= 35 * 35) {
        return 40;
      }
      return -1000;
    };
  }

  private static int sq(int v) {
    return v * v;
  }

  @Test
  void fitsTheBonesAndLeavesOutTableAirAndSpecks() {
    Optional<CropBox> box = BoneExtent.find(legs(true, true), SIZE, MM, 300, 8, () -> false);
    assertTrue(box.isPresent());
    CropBox b = box.get();
    // Bones span x 27..73, y 37..53, z 10..50; 8 mm margin each side.
    assertEquals(0.19, b.minX(), 1e-9);
    assertEquals(0.82, b.maxX(), 1e-9);
    assertEquals(0.29, b.minY(), 1e-9);
    assertEquals(0.62, b.maxY(), 1e-9);
    assertEquals(2 / 60.0, b.minZ(), 1e-9);
    assertEquals(59 / 60.0, b.maxZ(), 1e-9);
    assertTrue(b.maxY() < 0.88, "the table is left out");
  }

  @Test
  void sameBoxWithoutTableOrSpeck() {
    CropBox with = BoneExtent.find(legs(true, true), SIZE, MM, 300, 8, () -> false).orElseThrow();
    CropBox without =
        BoneExtent.find(legs(false, false), SIZE, MM, 300, 8, () -> false).orElseThrow();
    assertEquals(without, with);
  }

  @Test
  void noBoneMeansNoCrop() {
    VoxelSampler soft = (x, y, z) -> 40;
    assertTrue(BoneExtent.find(soft, SIZE, MM, 300, 8, () -> false).isEmpty());
  }

  @Test
  void cancelledSearchGivesNothing() {
    assertTrue(BoneExtent.find(legs(true, true), SIZE, MM, 300, 8, () -> true).isEmpty());
  }

  @Test
  void aLargeVolumeIsSampledCoarselyButStillFitsTheBone() {
    Vector3i big = new Vector3i(512, 512, 40);
    VoxelSampler bone =
        (x, y, z) -> x >= 200 && x < 300 && y >= 150 && y < 260 && z >= 5 && z < 35 ? 800 : -900;
    CropBox b =
        BoneExtent.find(bone, big, new Vector3d(0.5, 0.5, 1), 300, 0, () -> false).orElseThrow();
    // Stride 4: the box is right to within one sample.
    assertEquals(200 / 512.0, b.minX(), 4 / 512.0);
    assertEquals(300 / 512.0, b.maxX(), 4 / 512.0);
    assertEquals(150 / 512.0, b.minY(), 4 / 512.0);
    assertEquals(260 / 512.0, b.maxY(), 4 / 512.0);
  }

  @Test
  void aThickWidePieceIsBoneNotTable() {
    int[] size = {100, 100, 10};
    BoneExtent.Piece pelvis =
        new BoneExtent.Piece(5000, new int[] {10, 50, 0}, new int[] {90, 90, 9});
    BoneExtent.Piece table =
        new BoneExtent.Piece(2000, new int[] {5, 88, 0}, new int[] {95, 90, 9});
    BoneExtent.Piece frontPlate =
        new BoneExtent.Piece(2000, new int[] {5, 10, 0}, new int[] {95, 12, 9});
    assertFalse(BoneExtent.isTable(pelvis, size, MM));
    assertTrue(BoneExtent.isTable(table, size, MM));
    assertFalse(BoneExtent.isTable(frontPlate, size, MM), "a thin plate in front is not the table");
    List<BoneExtent.Piece> kept = BoneExtent.keep(List.of(pelvis, table), size, MM);
    assertEquals(List.of(pelvis), kept);
  }
}
