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

import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.RegionGrow.SeedException;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class RegionGrowTest {

  private static final GrowOptions RAW =
      GrowOptions.defaults().withClosingRadius(0).withFillHoles(false);

  private static Result grow(Phantom p, int x, int y, int z, VoxelBox crop, GrowOptions options)
      throws SeedException {
    return RegionGrow.grow(p.sizeX, p.sizeY, p.sizeZ, p, x, y, z, crop, options, GrowProgress.NONE);
  }

  private static long count(Phantom p, Phantom.Shape inside) {
    long n = 0;
    for (int z = 0; z < p.sizeZ; z++) {
      for (int y = 0; y < p.sizeY; y++) {
        for (int x = 0; x < p.sizeX; x++) {
          if (inside.hu(x, y, z) != 0) {
            n++;
          }
        }
      }
    }
    return n;
  }

  /** Whether the result holds exactly the voxels of {@code expected}. */
  private static void assertSelects(Phantom p, Result r, Phantom.Shape expected) {
    for (int z = 0; z < p.sizeZ; z++) {
      for (int y = 0; y < p.sizeY; y++) {
        for (int x = 0; x < p.sizeX; x++) {
          boolean want = expected.hu(x, y, z) != 0;
          assertEquals(want, r.contains(x, y, z), "voxel " + x + ", " + y + ", " + z);
        }
      }
    }
  }

  @Test
  void bridgedBonesAreGrownTogetherWithoutSeparation() throws SeedException {
    Phantom p = Phantom.twoBones();

    Result raw = grow(p, 20, 20, 10, null, RAW);
    Result closed = grow(p, 20, 20, 10, null, GrowOptions.defaults());

    long upper = count(p, (x, y, z) -> Phantom.upperBone(x, y, z) ? 1 : 0);
    long lower = count(p, (x, y, z) -> Phantom.lowerBone(x, y, z) ? 1 : 0);
    assertEquals(upper + lower + 2, raw.voxels(), "both bones and the two bridge voxels");
    assertEquals(raw.grownVoxels(), raw.voxels());
    assertFalse(raw.separated());
    assertTrue(closed.contains(20, 20, 30) && closed.contains(20, 20, 10));
  }

  @Test
  void separationKeepsOnlyTheSeededBone() throws SeedException {
    Phantom p = Phantom.twoBones();

    Result r = grow(p, 20, 20, 10, null, RAW.withSeparation(2));

    assertTrue(r.separated());
    // The bridge is split half-way: its upper voxel is nearer the upper bone.
    assertSelects(
        p, r, (x, y, z) -> Phantom.upperBone(x, y, z) || (x == 20 && y == 20 && z == 18) ? 1 : 0);
  }

  @Test
  void separationAndClosingKeepTheOtherBoneOut() throws SeedException {
    Phantom p = Phantom.twoBones();

    // Seeded in the lower bone, with the default closing and hole filling after the split.
    Result r = grow(p, 22, 18, 30, null, GrowOptions.defaults().withSeparation(2));

    for (int z = 0; z < 19; z++) {
      assertFalse(r.hasVoxelsInSlice(z), "slice " + z);
    }
    for (int z = 20; z <= 37; z++) {
      for (int y = 0; y < 40; y++) {
        for (int x = 0; x < 40; x++) {
          if (Phantom.lowerBone(x, y, z)) {
            assertTrue(r.contains(x, y, z), "voxel " + x + ", " + y + ", " + z);
          }
        }
      }
    }
  }

  @Test
  void closingAndHoleFillingPutTheMarrowInsideTheBone() throws SeedException {
    // A tube of 700 HU (radius 8) around a 50 HU marrow channel (radius 4) along z, its wall cut
    // by a one-voxel slit of marrow at x = 20, y 24 to 28, through every slice.
    Phantom p =
        new Phantom(
            40,
            40,
            20,
            (x, y, z) -> {
              if (Phantom.inDisc(x, y, 20, 20, 4) || (x == 20 && y >= 24 && y <= 28)) {
                return 50;
              }
              return Phantom.inDisc(x, y, 20, 20, 8) ? 700 : -100;
            });

    Result raw = grow(p, 27, 20, 10, null, RAW);
    Result filledOnly = grow(p, 27, 20, 10, null, RAW.withFillHoles(true));
    Result closedAndFilled = grow(p, 27, 20, 10, null, GrowOptions.defaults());

    assertFalse(raw.contains(20, 20, 10));
    assertFalse(filledOnly.contains(20, 20, 10), "the slit opens the marrow to the outside");
    assertTrue(closedAndFilled.contains(20, 20, 10), "closing seals the slit, filling the marrow");
    // The outer end of the slit stays open: the closing seals it two voxels deep.
    for (int z = 0; z < 20; z++) {
      for (int y = 0; y < 40; y++) {
        for (int x = 0; x < 40; x++) {
          if (Phantom.inDisc(x, y, 20, 20, 6)) {
            assertTrue(closedAndFilled.contains(x, y, z), "voxel " + x + ", " + y + ", " + z);
          } else if (!Phantom.inDisc(x, y, 20, 20, 9)) {
            assertFalse(closedAndFilled.contains(x, y, z), "voxel " + x + ", " + y + ", " + z);
          }
        }
      }
    }
  }

  @Test
  void huBoundsAreRespected() throws SeedException {
    // A 700 HU disc holding a 4000 HU metal core, inside a 150 HU rim.
    Phantom p =
        new Phantom(
            30,
            30,
            6,
            (x, y, z) -> {
              if (Phantom.inDisc(x, y, 15, 15, 2)) {
                return 4000;
              }
              if (Phantom.inDisc(x, y, 15, 15, 8)) {
                return 700;
              }
              return Phantom.inDisc(x, y, 15, 15, 11) ? 150 : 0;
            });

    Result standard = grow(p, 15, 21, 3, null, RAW);
    Result lowered = grow(p, 15, 21, 3, null, RAW.withRange(100, 3000));
    Result metal = grow(p, 15, 21, 3, null, RAW.withRange(100, 5000));

    for (int z = 0; z < 6; z++) {
      for (int y = 0; y < 30; y++) {
        for (int x = 0; x < 30; x++) {
          boolean bone = Phantom.inDisc(x, y, 15, 15, 8) && !Phantom.inDisc(x, y, 15, 15, 2);
          boolean rim = Phantom.inDisc(x, y, 15, 15, 11) && !Phantom.inDisc(x, y, 15, 15, 8);
          boolean core = Phantom.inDisc(x, y, 15, 15, 2);
          assertEquals(bone, standard.contains(x, y, z));
          assertEquals(bone || rim, lowered.contains(x, y, z));
          assertEquals(bone || rim || core, metal.contains(x, y, z));
        }
      }
    }
  }

  @Test
  void cropBoxIsRespected() throws SeedException {
    Phantom p = Phantom.twoBones();
    VoxelBox crop = new VoxelBox(0, 39, 0, 39, 0, 18);

    Result r = grow(p, 20, 20, 10, crop, RAW);

    assertEquals(new VoxelBox(12, 28, 12, 28, 2, 18), r.box(), "the bone's own block");
    assertTrue(r.contains(20, 20, 18));
    for (int z = 19; z < 40; z++) {
      assertFalse(r.hasVoxelsInSlice(z), "slice " + z);
    }
    // Only the box is read, and the region was read at most once per voxel.
    assertTrue(p.reads() <= crop.voxels() + 2);
    SeedException outside = assertThrows(SeedException.class, () -> grow(p, 20, 20, 30, crop, RAW));
    assertEquals(SeedException.Reason.OUTSIDE_BOX, outside.reason());
  }

  @Test
  void seedInTheMarrowMovesToTheNearestBone() throws SeedException {
    Phantom p =
        new Phantom(
            30,
            30,
            4,
            (x, y, z) ->
                Phantom.inDisc(x, y, 15, 15, 3) ? 50 : Phantom.inDisc(x, y, 15, 15, 7) ? 700 : 0);

    Result r = grow(p, 15, 13, 1, null, RAW);
    SeedException far =
        assertThrows(SeedException.class, () -> grow(p, 15, 15, 1, null, RAW.withSeedSearch(1)));

    assertEquals(700, r.seedHu());
    assertEquals(2, Math.abs(r.seed()[1] - 13) + Math.abs(r.seed()[0] - 15));
    assertEquals(SeedException.Reason.OUT_OF_RANGE, far.reason());
    assertEquals(50, far.hu());
  }

  @Test
  void smallPiecesAreDroppedButNotTheSeededOne() {
    BitVolume mask = new BitVolume(20, 20, 20);
    for (int z = 2; z < 12; z++) {
      for (int y = 2; y < 12; y++) {
        for (int x = 2; x < 12; x++) {
          mask.set(x, y, z);
        }
      }
    }
    mask.set(16, 16, 16);
    mask.set(16, 16, 17);
    BitVolume copy = mask.copy();

    int dropped = RegionGrow.dropSmallPieces(mask, 10, new int[] {5, 5, 5}, Thread.currentThread());
    int none =
        RegionGrow.dropSmallPieces(copy, 2000, new int[] {16, 16, 16}, Thread.currentThread());

    assertEquals(1, dropped);
    assertEquals(1000, mask.count());
    assertEquals(1, none, "the large block goes, the seeded pair stays");
    assertTrue(copy.get(16, 16, 17));
    assertEquals(2, copy.count());
  }

  @Test
  void growIsCancelledWhenTheThreadIsInterrupted() {
    Phantom solid = new Phantom(80, 80, 40, (_, _, _) -> 700);
    HuSource interrupting =
        (x, y, z) -> {
          if (solid.reads() == 1000) {
            Thread.currentThread().interrupt();
          }
          return solid.huAt(x, y, z);
        };
    try {
      assertThrows(
          CancellationException.class,
          () ->
              RegionGrow.grow(80, 80, 40, interrupting, 40, 40, 20, null, RAW, GrowProgress.NONE));
      assertTrue(solid.reads() < 80 * 80 * 40, "stopped before reading the whole volume");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void progressReachesEveryStage() throws SeedException {
    Phantom p = Phantom.twoBones();
    java.util.Set<GrowProgress.Stage> seen = java.util.EnumSet.noneOf(GrowProgress.Stage.class);

    RegionGrow.grow(
        40,
        40,
        40,
        p,
        20,
        20,
        10,
        null,
        GrowOptions.defaults().withSeparation(1),
        (stage, _) -> {
          synchronized (seen) {
            seen.add(stage);
          }
        });

    assertEquals(java.util.EnumSet.allOf(GrowProgress.Stage.class), seen);
  }

  @Test
  void volumeIsReportedInCubicCentimetres() throws SeedException {
    Phantom p = new Phantom(20, 20, 20, (x, y, z) -> x < 10 ? 700 : 0);

    Result r = grow(p, 2, 2, 2, null, RAW);

    assertEquals(4000, r.voxels());
    assertEquals(4000 * 0.5 * 0.5 * 1.25 / 1000, r.volumeCm3(0.5 * 0.5 * 1.25), 1e-12);
  }
}
