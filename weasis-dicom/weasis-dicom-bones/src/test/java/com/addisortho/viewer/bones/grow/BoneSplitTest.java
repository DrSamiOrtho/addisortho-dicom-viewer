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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.grow.BoneSplit.Split;
import com.addisortho.viewer.bones.grow.BoneSplit.SplitException;
import com.addisortho.viewer.bones.grow.RegionGrow.SeedException;
import org.junit.jupiter.api.Test;

class BoneSplitTest {

  private static final GrowOptions RAW =
      GrowOptions.defaults().withClosingRadius(0).withFillHoles(false);

  /** The femur and tibia of the phantom grown as one bone through the bridge of the joint. */
  private static BoxMask bothBones() throws SeedException {
    Phantom p = Phantom.twoBones();
    return BoxMask.of(
        RegionGrow.grow(p.sizeX, p.sizeY, p.sizeZ, p, 20, 20, 10, null, RAW, GrowProgress.NONE));
  }

  /** Two cubes of 6 voxels, apart: x 0 to 5 and x 10 to 15, in a block of 16 x 6 x 6. */
  private static BoxMask twoCubes() {
    BitVolume bits = new BitVolume(16, 6, 6);
    for (int z = 0; z < 6; z++) {
      for (int y = 0; y < 6; y++) {
        for (int x = 0; x < 16; x++) {
          if (x <= 5 || x >= 10) {
            bits.set(x, y, z);
          }
        }
      }
    }
    return new BoxMask(new VoxelBox(100, 115, 50, 55, 20, 25), bits);
  }

  @Test
  void splitsAtTheNarrowestLink() throws Exception {
    BoxMask both = bothBones();
    Split split = BoneSplit.split(both, new int[] {20, 20, 10}, new int[] {20, 20, 30}, 12);

    assertTrue(split.depth() >= 1, "the bridge is cut by erosion");
    assertEquals(both.count(), split.kept().count() + split.cut().count(), "every voxel kept once");
    for (int z = 0; z < 40; z++) {
      for (int y = 0; y < 40; y++) {
        for (int x = 0; x < 40; x++) {
          if (Phantom.upperBone(x, y, z)) {
            assertTrue(split.kept().contains(x, y, z), "femur voxel " + x + ", " + y + ", " + z);
            assertFalse(split.cut().contains(x, y, z));
          } else if (Phantom.lowerBone(x, y, z)) {
            assertTrue(split.cut().contains(x, y, z), "tibia voxel " + x + ", " + y + ", " + z);
            assertFalse(split.kept().contains(x, y, z));
          }
        }
      }
    }
  }

  @Test
  void aStartingPointNoLongerInTheBoneFallsBackToTheFarthestPart() throws Exception {
    BoxMask both = bothBones();
    Split split = BoneSplit.split(both, new int[] {0, 0, 0}, new int[] {20, 20, 30}, 12);
    assertTrue(split.kept().contains(20, 20, 10), "the femur, far from the click, is kept");
    assertTrue(split.cut().contains(20, 20, 30));
  }

  @Test
  void aLoosePieceComesAwayWithoutErosion() throws Exception {
    BoxMask cubes = twoCubes();
    Split split = BoneSplit.split(cubes, new int[] {102, 52, 22}, new int[] {112, 52, 22}, 12);
    assertEquals(0, split.depth());
    assertEquals(216, split.kept().count());
    assertEquals(216, split.cut().count());
    assertEquals(new VoxelBox(110, 115, 50, 55, 20, 25), split.cut().box());
    assertEquals(new VoxelBox(100, 105, 50, 55, 20, 25), split.kept().box());
  }

  @Test
  void aSolidBlockCannotBeSplit() {
    BitVolume bits = new BitVolume(12, 12, 12);
    for (int z = 0; z < 12; z++) {
      for (int y = 0; y < 12; y++) {
        for (int x = 0; x < 12; x++) {
          bits.set(x, y, z);
        }
      }
    }
    BoxMask block = new BoxMask(new VoxelBox(0, 11, 0, 11, 0, 11), bits);
    SplitException e =
        assertThrows(
            SplitException.class,
            () -> BoneSplit.split(block, new int[] {2, 2, 2}, new int[] {9, 9, 9}, 12));
    assertEquals(SplitException.Reason.NOT_SEPARABLE, e.reason());
  }

  @Test
  void aClickOutsideTheBoneIsRefused() {
    BoxMask cubes = twoCubes();
    SplitException e =
        assertThrows(
            SplitException.class,
            () -> BoneSplit.split(cubes, new int[] {102, 52, 22}, new int[] {107, 52, 22}, 12));
    assertEquals(SplitException.Reason.NOT_IN_BONE, e.reason());
  }
}
