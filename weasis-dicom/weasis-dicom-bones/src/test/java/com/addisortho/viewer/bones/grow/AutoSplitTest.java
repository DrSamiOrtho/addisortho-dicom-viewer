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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.grow.AutoSplit.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class AutoSplitTest {

  private static Result split(Phantom p, AutoSplit.Options options) {
    return AutoSplit.split(
        VoxelBox.of(p.sizeX, p.sizeY, p.sizeZ), () -> p, AutoSplit.Shown.ALL, options);
  }

  private static Result split(Phantom p) {
    return split(p, AutoSplit.Options.of(1, 1, 1));
  }

  @Test
  void bonesTouchingAtAPointAreTwo() {
    Result r = split(Phantom.twoBones());
    assertEquals(2, r.pieces().size());
    long all = r.pieces().stream().mapToLong(piece -> piece.mask().count()).sum();
    // Both cylinders and the bridge: nothing is lost between the two bones.
    assertTrue(all >= 197 * 34, "voxels " + all);
  }

  @Test
  void aBoneWithANarrowNeckStaysOne() {
    Phantom neck =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> {
              double radius = z >= 17 && z <= 21 ? 5 : 8;
              return z >= 2 && z <= 37 && Phantom.inDisc(x, y, 20, 20, radius) ? 700 : 0;
            });
    assertEquals(1, split(neck).pieces().size());
  }

  @Test
  void aSmallFragmentJoinsTheBoneNearIt() {
    Phantom fractured =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> {
              if (z >= 2 && z <= 37 && Phantom.inDisc(x, y, 15, 20, 8)) {
                return 700;
              }
              // A 5 mm cube 3 mm beside the shaft.
              return x >= 26 && x <= 30 && y >= 18 && y <= 22 && z >= 18 && z <= 22 ? 700 : 0;
            });
    Result r = split(fractured);
    assertEquals(1, r.pieces().size());
    assertEquals(1, r.pieces().getFirst().fragments());
    assertEquals(1, r.fragments());
  }

  @Test
  void aCoarseGridStillSplitsTheJoint() {
    Result r = split(Phantom.twoBones(), AutoSplit.Options.of(1, 1, 1).withMaxWorkVoxels(10_000));
    assertTrue(r.factor() > 1);
    assertEquals(2, r.pieces().size());
  }

  @Test
  void noBoneGivesNoPiece() {
    Phantom empty = new Phantom(10, 10, 10, (_, _, _) -> 0);
    assertTrue(split(empty).pieces().isEmpty());
  }

  /** A cylinder of radius 8 along z, 2 to 37, narrowed to radius 3 at z 19 and 20. */
  private static BoxMask waisted() {
    BitVolume bits = new BitVolume(40, 40, 40);
    for (int z = 2; z <= 37; z++) {
      double radius = z == 19 || z == 20 ? 3 : 8;
      for (int y = 0; y < 40; y++) {
        for (int x = 0; x < 40; x++) {
          if (Phantom.inDisc(x, y, 20, 20, radius)) {
            bits.set(x, y, z);
          }
        }
      }
    }
    return new BoxMask(VoxelBox.of(40, 40, 40), bits);
  }

  @Test
  void twoMarksSplitAtTheNeckBetweenThem() throws Exception {
    BoneSplit.Split split =
        AutoSplit.splitBetween(waisted(), new int[] {20, 20, 5}, new int[] {20, 20, 34}, 1, 1, 1);
    // The cut runs through the narrow part, not half-way between the marks.
    assertTrue(split.kept().box().z1() <= 21, "kept to z " + split.kept().box().z1());
    assertTrue(split.cut().box().z0() >= 18, "cut from z " + split.cut().box().z0());
    assertEquals(waisted().count(), split.kept().count() + split.cut().count());
  }

  @Test
  void aMarkOffTheBoneIsRefused() {
    assertThrows(
        BoneSplit.SplitException.class,
        () ->
            AutoSplit.splitBetween(
                waisted(), new int[] {20, 20, 5}, new int[] {0, 0, 0}, 1, 1, 1));
  }

  @Test
  void unionHoldsEveryVoxel() throws Exception {
    BoneSplit.Split split =
        AutoSplit.splitBetween(waisted(), new int[] {20, 20, 5}, new int[] {20, 20, 34}, 1, 1, 1);
    BoxMask joined = BoxMask.union(List.of(split.kept(), split.cut()));
    assertEquals(waisted().count(), joined.count());
    assertTrue(joined.contains(20, 20, 5) && joined.contains(20, 20, 34));
  }

  /**
   * A fragment pressed against a bone over its whole face, with no neck: one 20 by 20 block, bone
   * of 1000 HU, crossed at z 12 by a fracture line of 250 HU that the bone mask holds. The split
   * between two marks, one on each side, follows the line, not the marks.
   */
  @Test
  void twoMarksSplitAlongTheFractureLineWithTheCt() throws Exception {
    Phantom ct =
        new Phantom(
            30,
            30,
            30,
            (x, y, z) -> {
              boolean inBlock = x >= 5 && x <= 24 && y >= 5 && y <= 24 && z >= 3 && z <= 22;
              if (!inBlock) {
                return 0;
              }
              return z == 12 ? 250 : 1000;
            });
    BitVolume bits = new BitVolume(30, 30, 30);
    for (int z = 3; z <= 22; z++) {
      for (int y = 5; y <= 24; y++) {
        for (int x = 5; x <= 24; x++) {
          bits.set(x, y, z);
        }
      }
    }
    BoxMask block = new BoxMask(VoxelBox.of(30, 30, 30), bits);
    BoneSplit.Split split =
        AutoSplit.splitBetween(block, new int[] {15, 15, 6}, new int[] {15, 15, 15}, 1, 1, 1, ct);
    // The voxels of the line itself may go either way.
    int z0 = split.cut().box().z0();
    assertTrue(z0 == 12 || z0 == 13, "the fragment starts at the fracture line, not at " + z0);
    long voxels = split.cut().count();
    assertTrue(
        voxels >= 20 * 20 * 10 && voxels <= 20 * 20 * 11,
        "the whole fragment, z 13 to 22, with at most the line: " + voxels);
  }
}
