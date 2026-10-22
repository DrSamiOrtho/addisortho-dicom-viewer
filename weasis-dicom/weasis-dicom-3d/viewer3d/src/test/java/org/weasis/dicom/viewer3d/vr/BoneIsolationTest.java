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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.vr.BoneIsolation.Result;

/**
 * A synthetic forearm in plaster, 1 mm voxels: a bone cylinder with a marrow canal inside a
 * soft-tissue cylinder inside a plaster ring that touches the outside air, a table slab behind, and
 * on request a nail in the canal, a plate on the bone, a fixator pin out through the cast, or an
 * air pocket beside the bone that reaches the outside air in three dimensions but not within any
 * slice.
 */
class BoneIsolationTest {

  private static final int W = 96;
  private static final int H = 96;
  private static final int D = 40;
  private static final Vector3i SIZE = new Vector3i(W, H, D);
  private static final VolumeSpace SPACE = VolumeSpace.ofVolume(SIZE, new Vector3d(1, 1, 1));
  private static final int CX = 40;
  private static final int CY = 40;

  private record Forearm(
      boolean bone,
      int boneZ0,
      int boneZ1,
      boolean nail,
      boolean plate,
      boolean pin,
      boolean pocket) {

    static Forearm plain() {
      return new Forearm(true, 4, 35, false, false, false, false);
    }

    float value(int x, int y, int z) {
      int dx = x - CX;
      int dy = y - CY;
      int r2 = dx * dx + dy * dy;
      boolean inBoneZ = z >= boneZ0 && z <= boneZ1;
      if (pin && z >= 19 && z <= 21 && y >= 39 && y <= 41 && x >= CX && x <= 95) {
        return 3500; // a 3 by 3 rod from the bone out through the cast into the air
      }
      if (plate && x >= 47 && x <= 48 && y >= 36 && y <= 44 && z >= 8 && z <= 30) {
        return 3500; // on the bone's surface, under the soft tissue
      }
      if (pocket && isPocket(x, y, z)) {
        return -1000;
      }
      if (bone && inBoneZ && r2 <= 4) {
        return nail ? 3500 : 40; // the marrow canal, or a nail filling it
      }
      if (bone && inBoneZ && r2 <= 36) {
        return 900;
      }
      if (r2 <= 18 * 18) {
        return 40;
      }
      if (r2 <= 23 * 23) {
        return 1200; // plaster, as dense as bone, touching the skin and the air
      }
      if (y >= 80 && y <= 83 && x >= 5 && x <= 90) {
        return 500; // the table
      }
      return -1000;
    }

    /**
     * Air beside the bone at z 10..30, a shaft up to z 38, and there a channel out to the border:
     * joined to the outside air in three dimensions, enclosed within every slice but z 38.
     */
    private static boolean isPocket(int x, int y, int z) {
      boolean column = x >= 47 && x <= 49 && y >= 39 && y <= 41 && z >= 10 && z <= 38;
      boolean channel = z == 38 && y == 40 && x >= 47;
      return column || channel;
    }

    BoneExtent.SliceSource slices() {
      return z -> {
        float[] s = new float[W * H];
        for (int y = 0, i = 0; y < H; y++) {
          for (int x = 0; x < W; x++, i++) {
            s[i] = value(x, y, z);
          }
        }
        return s;
      };
    }

    Result isolate() {
      return find().orElseThrow();
    }

    Optional<Result> find() {
      return BoneIsolation.find(SPACE, slices(), 0, 300, () -> false, new WorkingMemory(64L << 20));
    }
  }

  @Test
  void theCastAndTheTableGoTheBoneStaysWithItsCanal() {
    Result r = Forearm.plain().isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(2, r.externalPieces(), "the cast and the table"),
        () -> assertEquals(0, r.implants()),
        () -> assertTrue(kept.get(CX + 6, CY, 20), "cortex"),
        () -> assertTrue(kept.get(CX, CY, 20), "the marrow canal is filled"),
        () -> assertTrue(kept.get(CX + 8, CY, 20), "two voxels of margin"),
        () -> assertFalse(kept.get(CX + 9, CY, 20), "not three"),
        () -> assertFalse(kept.get(CX + 12, CY, 20), "soft tissue"),
        () -> assertFalse(kept.get(CX + 20, CY, 20), "plaster"),
        () -> assertFalse(kept.get(CX, 81, 20), "table"),
        () -> assertFalse(kept.get(CX, CY, 1), "below the bone"),
        () -> assertFalse(kept.get(CX, CY, 38), "above the bone"));
    // The bone with its canal, widened by two steps of 6-connected dilation, and nothing else.
    assertEquals(expectedPlainVoxels(), r.voxels());
  }

  private static long expectedPlainVoxels() {
    long n = 0;
    for (int z = 0; z < D; z++) {
      for (int y = 0; y < H; y++) {
        for (int x = 0; x < W; x++) {
          if (nearBone(x, y, z)) {
            n++;
          }
        }
      }
    }
    return n;
  }

  private static boolean nearBone(int x, int y, int z) {
    for (int dz = -2; dz <= 2; dz++) {
      for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
          int bz = z + dz;
          int bx = x + dx - CX;
          int by = y + dy - CY;
          if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) <= 2
              && bz >= 4
              && bz <= 35
              && bx * bx + by * by <= 36) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Test
  void aNailInTheCanalAndAPlateOnTheBoneStayWhole() {
    Result r = new Forearm(true, 4, 35, true, true, false, false).isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(2, r.implants()),
        () -> assertTrue(kept.get(CX, CY, 20), "nail"),
        () -> assertTrue(kept.get(CX, CY, 34), "nail, top"),
        () -> assertTrue(kept.get(48, 44, 30), "the plate's far corner"),
        () -> assertFalse(kept.get(CX + 12, CY, 20), "soft tissue"));
  }

  @Test
  void aFixatorPinIsCutWhereItLeavesTheBone() {
    Result r = new Forearm(true, 4, 35, false, false, true, false).isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(1, r.bonePieces(), "the pin does not join the bone to the outside"),
        () -> assertEquals(0, r.implants()),
        () -> assertTrue(kept.get(CX + 3, CY, 20), "the pin inside the bone"),
        () -> assertTrue(kept.get(CX + 6, CY, 20), "the pin at the bone's surface"),
        () -> assertFalse(kept.get(CX + 15, CY, 20), "the pin in the soft tissue"),
        () -> assertFalse(kept.get(CX + 20, CY, 20), "the pin in the plaster"),
        () -> assertFalse(kept.get(CX + 40, CY, 20), "the pin in the air"));
  }

  @Test
  void aBoneCutByTheFirstAndLastSliceStays() {
    Result r = new Forearm(true, 0, D - 1, false, false, false, false).isolate();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertTrue(r.region().get(CX, CY, 0)),
        () -> assertTrue(r.region().get(CX + 6, CY, D - 1)));
  }

  @Test
  void airIsOutsideOnlyWhenItReachesTheBorderWithinItsOwnSlice() {
    // The pocket touches the bone on 3 by 21 voxels and reaches the outside air through the shaft
    // and the channel of slice 38: a flood in three dimensions would call the bone external.
    Result r = new Forearm(true, 4, 35, false, false, false, true).isolate();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(2, r.externalPieces()),
        () -> assertTrue(r.region().get(CX + 6, CY, 20)));
  }

  @Test
  void withoutBoneNothingIsFound() {
    assertTrue(new Forearm(false, 0, 0, false, false, false, false).find().isEmpty());
  }

  @Test
  void theBorderFloodStopsAtEnclosedPocketsAndFollowsNotches() {
    // 10 by 5: a closed box on the left, a box open at its bottom on the right.
    String[] rows = {
      "..........", //
      ".###..###.", //
      ".#.#..#.#.", //
      ".###..#.#.", //
      ".........."
    };
    byte[] cell = new byte[10 * 5];
    for (int y = 0; y < 5; y++) {
      for (int x = 0; x < 10; x++) {
        cell[y * 10 + x] = (byte) (rows[y].charAt(x) == '#' ? 0 : 1);
      }
    }
    WorkingMemory budget = new WorkingMemory(1 << 20);
    VoxelFlood.IntQueue queue = new VoxelFlood.IntQueue(16, budget);
    BoneIsolation.floodFromBorder(cell, 10, 5, queue);
    queue.free();
    assertAll(
        () -> assertEquals(1, cell[2 * 10 + 2], "the closed box stays open, unreached"),
        () -> assertEquals(2, cell[2 * 10 + 7], "the open box is reached from below"),
        () -> assertEquals(2, cell[0], "the border"),
        () -> assertEquals(2, cell[2 * 10 + 4], "the channel between the boxes"),
        () -> assertEquals(0, cell[1 * 10 + 1], "walls are untouched"));
  }

  @Test
  void dilationIsSixConnectedAndNeverWrapsARow() {
    VoxelBits mask = new VoxelBits(70, 10, 10);
    VoxelBits scratch = new VoxelBits(70, 10, 10);
    mask.set(30, 5, 5);
    mask.set(0, 2, 2);
    BoneIsolation.dilate(mask, scratch, 2, new VoxelFlood.Box(0, 30, 2, 5, 2, 5));
    assertAll(
        () -> assertEquals(25 + 19, mask.count(), "two balls of radius 2, one cut at x = 0"),
        () -> assertTrue(mask.get(32, 5, 5)),
        () -> assertTrue(mask.get(30, 5, 7)),
        () -> assertTrue(mask.get(31, 6, 5)),
        () -> assertFalse(mask.get(31, 6, 6), "corner at distance 3"),
        () -> assertTrue(mask.get(2, 2, 2)),
        () -> assertFalse(mask.get(69, 1, 2), "nothing leaks into the previous row"),
        () -> assertFalse(mask.get(68, 2, 2)),
        () -> assertEquals(0, scratch.count(), "the scratch is clear again"));
  }

  @Test
  void shiftsCrossWordBoundariesBothWays() {
    long[] src = new long[3];
    src[0] = 1L << 63;
    src[1] = 1L;
    long[] dst = new long[3];
    BoneIsolation.shiftInto(dst, src, 1);
    assertAll(() -> assertEquals(0L, dst[0]), () -> assertEquals(3L, dst[1]));
    BoneIsolation.shiftInto(dst, src, -1);
    assertAll(
        () -> assertEquals(3L << 62, dst[0]),
        () -> assertEquals(0L, dst[1]),
        () -> assertEquals(0L, dst[2]));
    BoneIsolation.shiftInto(dst, src, 65);
    assertAll(() -> assertEquals(0L, dst[1]), () -> assertEquals(3L, dst[2]));
  }

  private static long countDisc(int radius) {
    long n = 0;
    for (int y = -radius; y <= radius; y++) {
      for (int x = -radius; x <= radius; x++) {
        if (x * x + y * y <= radius * radius) {
          n++;
        }
      }
    }
    return n;
  }
}
