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

  /**
   * Thick slices (review of 11 Nov 2026, B8): a plate two slices thick joined to a smaller one by a
   * neck two pixels wide, on 0.5 mm pixels and 3 mm slices. Eroding a voxel on every axis wore both
   * plates away at the first step, across the slices, and nothing could be erased; with the voxel
   * size the first steps work in the plane and the neck goes first.
   */
  @Test
  void onThickSlicesTheNeckIsCutInThePlaneBeforeTheSlicesAreEaten() throws Exception {
    BitVolume bits = new BitVolume(40, 20, 2);
    for (int z = 0; z < 2; z++) {
      for (int y = 0; y < 20; y++) {
        for (int x = 0; x < 40; x++) {
          boolean big = x <= 19;
          boolean neck = x >= 20 && x <= 25 && (y == 9 || y == 10);
          boolean small = x >= 26 && y >= 4 && y <= 15;
          if (big || neck || small) {
            bits.set(x, y, z);
          }
        }
      }
    }
    BoxMask bone = new BoxMask(new VoxelBox(0, 39, 0, 19, 0, 1), bits);
    int[] keep = {8, 10, 0};
    int[] cut = {33, 10, 1};

    SplitException e =
        assertThrows(SplitException.class, () -> BoneSplit.split(bone, keep, cut, 12));
    assertEquals(SplitException.Reason.NOT_SEPARABLE, e.reason(), "voxel counts: nothing is left");

    Split split = BoneSplit.split(bone, keep, cut, BoneSplit.depthFor(0.5, 0.5), 0.5, 0.5, 3);
    assertEquals(bone.count(), split.kept().count() + split.cut().count(), "every voxel kept once");
    assertTrue(split.kept().contains(8, 10, 0) && split.kept().contains(8, 10, 1));
    assertTrue(split.cut().contains(33, 10, 0) && split.cut().contains(33, 10, 1));
    assertTrue(
        split.cut().box().x0() >= 20,
        "the cut runs through the neck, from x " + split.cut().box().x0());
  }

  @Test
  void erosionCrossesTheSlicesOnlyAsOftenAsTheirDistanceAllows() {
    // Cubic voxels: every step, as before.
    for (int depth = 1; depth <= 12; depth++) {
      assertTrue(BoneSplit.acrossSlices(depth, 0.5, 0.5));
    }
    // 0.5 mm pixels, 3 mm slices: the sixth and the twelfth step.
    int across = 0;
    for (int depth = 1; depth <= 12; depth++) {
      boolean now = BoneSplit.acrossSlices(depth, 0.5, 3);
      assertEquals(depth == 6 || depth == 12, now, "step " + depth);
      across += now ? 1 : 0;
    }
    assertEquals(2, across);
    assertEquals(12, BoneSplit.depthFor(0.5, 0.5), "the depth the 12 voxels were set on");
    assertEquals(6, BoneSplit.depthFor(1.0, 1.0));
    assertEquals(21, BoneSplit.depthFor(0.29, 0.29));
    assertEquals(4, BoneSplit.depthFor(3, 3), "a coarse grid still gets a try");
  }
}
