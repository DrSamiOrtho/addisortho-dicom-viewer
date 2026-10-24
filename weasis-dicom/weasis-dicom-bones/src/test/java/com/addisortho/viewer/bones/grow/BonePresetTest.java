/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BonePresetTest {

  @Test
  void everyPresetMakesValidOptions() {
    for (BonePreset p : BonePreset.ALL) {
      GrowOptions o = p.options(0.5);
      assertEquals(p.lowerHu(), o.lowerHu());
      assertEquals(GrowOptions.DEFAULT_UPPER_HU, o.upperHu());
      assertTrue(o.fillHoles(), p.key());
      assertTrue(o.closingRadius() <= GrowOptions.MAX_RADIUS, p.key());
      assertTrue(o.separationVoxels() <= GrowOptions.MAX_RADIUS, p.key());
      assertEquals(Math.round(p.minPieceMm3() / 0.5), o.minPieceVoxels(), p.key());
    }
  }

  @Test
  void presetsAreDistinctAndKeepImplantsOut() {
    assertEquals(
        BonePreset.ALL.length,
        Arrays.stream(BonePreset.ALL).map(BonePreset::key).distinct().count());
    for (BonePreset p : BonePreset.ALL) {
      // Metal implants are above 3000 HU and must not join the bone.
      assertTrue(p.options(1).upperHu() <= 3000, p.key());
      assertTrue(p.lowerHu() >= 150 && p.lowerHu() <= 300, p.key());
    }
  }

  @Test
  void jointBonesAreSeparatedFromTheirNeighbour() {
    assertTrue(BonePreset.PELVIS.separationVoxels() > 0);
    assertTrue(BonePreset.FEMUR.separationVoxels() > 0);
    assertTrue(BonePreset.KNEE.separationVoxels() > 0);
    assertEquals(0, BonePreset.OTHER.separationVoxels());
  }

  @Test
  void unknownVoxelSizeKeepsEveryPiece() {
    assertEquals(0, BonePreset.KNEE.options(0).minPieceVoxels());
  }
}
