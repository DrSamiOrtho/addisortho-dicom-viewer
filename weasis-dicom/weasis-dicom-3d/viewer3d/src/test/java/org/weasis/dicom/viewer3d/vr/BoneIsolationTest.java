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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
  private static final Vector3d MM1 = new Vector3d(1, 1, 1);
  private static final VolumeSpace SPACE = VolumeSpace.ofVolume(SIZE, MM1);
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
      return BoneIsolation.find(
          SPACE, slices(), 0, MM1, 300, () -> false, new WorkingMemory(64L << 20));
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

  // ---- Pieces by size and nearness: fragments, specks, a marker, a finger ----

  /** A volume of shapes, the first shape holding a voxel giving its value, air elsewhere. */
  private static final class Scene {
    interface Shape {
      boolean holds(int x, int y, int z);
    }

    private record Part(Shape shape, float hu) {}

    private final Vector3i size;
    private final Vector3d mm;
    private final List<Part> parts = new ArrayList<>();

    Scene(int w, int h, int d, Vector3d mm) {
      this.size = new Vector3i(w, h, d);
      this.mm = mm;
    }

    Scene box(int x0, int x1, int y0, int y1, int z0, int z1, float hu) {
      parts.add(
          new Part(
              (x, y, z) -> x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1, hu));
      return this;
    }

    /** A cylinder along z; {@code rx} and {@code ry} are its radii in voxels. */
    Scene cylinder(int cx, int cy, double rx, double ry, int z0, int z1, float hu) {
      parts.add(
          new Part(
              (x, y, z) -> {
                double dx = (x - cx) / rx;
                double dy = (y - cy) / ry;
                return z >= z0 && z <= z1 && dx * dx + dy * dy <= 1;
              },
              hu));
      return this;
    }

    float value(int x, int y, int z) {
      for (Part p : parts) {
        if (p.shape().holds(x, y, z)) {
          return p.hu();
        }
      }
      return -1000;
    }

    Result isolate() {
      VolumeSpace space = VolumeSpace.ofVolume(size, mm);
      BoneExtent.SliceSource slices =
          z -> {
            float[] s = new float[size.x * size.y];
            for (int y = 0, i = 0; y < size.y; y++) {
              for (int x = 0; x < size.x; x++, i++) {
                s[i] = value(x, y, z);
              }
            }
            return s;
          };
      return BoneIsolation.find(
              space, slices, 0, mm, 300, () -> false, new WorkingMemory(64L << 20))
          .orElseThrow();
    }

    long keptIn(VoxelBits kept, int x0, int x1, int y0, int y1, int z0, int z1) {
      long n = 0;
      for (int z = z0; z <= z1; z++) {
        for (int y = y0; y <= y1; y++) {
          for (int x = x0; x <= x1; x++) {
            if (kept.get(x, y, z)) {
              n++;
            }
          }
        }
      }
      return n;
    }
  }

  /** A bone cylinder of radius 6 mm in soft tissue, 1 mm voxels, no cast. */
  private static Scene bare() {
    return new Scene(W, H, D, MM1)
        .cylinder(CX, CY, 6, 6, 4, 35, 900)
        .cylinder(CX, CY, 18, 18, 0, D - 1, 40);
  }

  @Test
  void aFragmentTwoMillimetresFromTheBoneStaysAFarOneGoes() {
    // The cortex ends at x = 46; a 27 mm3 cube at x 49..51 is 2 mm away, one at 56..58 is 5 mm
    // from that one and 10 mm from the cortex.
    Scene scene =
        new Scene(W, H, D, MM1)
            .box(49, 51, 39, 41, 18, 20, 900)
            .box(56, 58, 39, 41, 18, 20, 900)
            .cylinder(CX, CY, 6, 6, 4, 35, 900)
            .cylinder(CX, CY, 18, 18, 0, D - 1, 40);
    Result r = scene.isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(1, r.joinedPieces(), "the near fragment"),
        () -> assertEquals(1, r.droppedPieces(), "the far fragment"),
        () -> assertEquals(0, r.externalPieces()),
        () -> assertTrue(kept.get(50, 40, 19), "the near fragment"),
        () -> assertFalse(kept.get(57, 40, 19), "the far fragment"),
        () -> assertTrue(kept.get(CX, CY, 20), "the bone"));
  }

  @Test
  void specksInAFoamPadGoAndLeaveNoSheet() {
    // Voxels of 0.5 by 0.5 by 2 mm. A pad of foam denser than air lies against the skin at y 70
    // and up, full of specks of 4 voxels (2 mm3) on a lattice; the bone is 9 mm from the pad.
    Vector3d mm = new Vector3d(0.5, 0.5, 2);
    Scene scene = new Scene(W, H, D, mm);
    for (int z = 1; z < D; z += 2) {
      for (int y = 72; y < H; y += 4) {
        for (int x = 2; x < W - 4; x += 6) {
          scene.box(x, x + 3, y, y, z, z, 600);
        }
      }
    }
    scene
        .cylinder(CX, CY, 12, 12, 2, 37, 900)
        .cylinder(CX, CY, 30, 30, 0, D - 1, 40)
        .box(0, W - 1, 70, H - 1, 0, D - 1, -200);
    Result r = scene.isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(0, r.joinedPieces()),
        () -> assertTrue(r.droppedPieces() > 100, "the specks: " + r.droppedPieces()),
        () -> assertEquals(0, r.externalPieces(), "the pad is not dense"),
        () -> assertEquals(0, scene.keptIn(kept, 0, W - 1, 70, H - 1, 0, D - 1), "no sheet"),
        () -> assertTrue(kept.get(CX, CY, 20), "the bone"),
        () -> assertTrue(kept.get(CX + 12, CY, 20), "the cortex"));
  }

  @Test
  void aMarkerTenMillimetresFromTheBoneGoesWithItsMetal() {
    // A lead marker (a 9 by 2 by 4 mm core above the metal threshold, a 1 mm shell of partial
    // volume around it) taped on at y 56..59, 10 mm from the cortex at y = 46, touching no air.
    Scene scene =
        new Scene(W, H, D, MM1)
            .box(36, 44, 57, 58, 18, 21, 3500)
            .box(35, 45, 56, 59, 17, 22, 1000)
            .cylinder(CX, CY, 6, 6, 4, 35, 900)
            .cylinder(CX, CY, 18, 18, 0, D - 1, 40)
            .box(0, W - 1, 52, 75, 0, D - 1, -200);
    Result r = scene.isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(0, r.joinedPieces()),
        () -> assertEquals(1, r.droppedPieces(), "the marker's shell"),
        () -> assertEquals(0, r.implants(), "the marker's core"),
        () -> assertFalse(kept.get(40, 57, 19), "the core"),
        () -> assertFalse(kept.get(35, 56, 17), "the shell"),
        () -> assertTrue(kept.get(CX, CY, 20), "the bone"));
  }

  @Test
  void aChainOfSmallPiecesThreeMillimetresApartStaysLikeAFinger() {
    // A metacarpal of 1.5 cm3, then three phalanges of under 1 cm3 each, 3 mm gaps between them,
    // all in soft tissue; 1 mm in plane, 1.5 mm slices.
    int d = 60;
    Scene scene =
        new Scene(W, H, d, new Vector3d(1, 1, 1.5))
            .cylinder(CX, CY, 5, 5, 2, 14, 900)
            .cylinder(CX, CY, 3, 3, 17, 22, 900)
            .cylinder(CX, CY, 3, 3, 25, 29, 900)
            .cylinder(CX, CY, 2.5, 2.5, 32, 35, 900)
            .cylinder(CX, CY, 18, 18, 0, d - 1, 40);
    Result r = scene.isolate();
    VoxelBits kept = r.region();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(3, r.joinedPieces()),
        () -> assertEquals(0, r.droppedPieces()),
        () -> assertTrue(kept.get(CX, CY, 20), "proximal"),
        () -> assertTrue(kept.get(CX, CY, 27), "middle"),
        () -> assertTrue(kept.get(CX, CY, 34), "distal"),
        () -> assertFalse(kept.get(CX, CY, 38), "beyond the fingertip"),
        () -> assertFalse(kept.get(CX, CY + 10, 27), "soft tissue"));
  }

  @Test
  void withoutABigPieceTheLargestSmallOneSeedsTheBone() {
    // A fingertip alone: two phalanges under 1 cm3 and a chip 6 mm off.
    Scene scene =
        new Scene(W, H, D, MM1)
            .cylinder(CX, CY, 3, 3, 4, 14, 900)
            .cylinder(CX, CY, 3, 3, 17, 22, 900)
            .box(CX + 10, CX + 11, CY, CY + 1, 8, 10, 900)
            .cylinder(CX, CY, 18, 18, 0, D - 1, 40);
    Result r = scene.isolate();
    assertAll(
        () -> assertEquals(1, r.bonePieces()),
        () -> assertEquals(1, r.joinedPieces()),
        () -> assertEquals(1, r.droppedPieces()),
        () -> assertTrue(r.region().get(CX, CY, 20)),
        () -> assertFalse(r.region().get(CX + 10, CY, 9)));
  }

  @Test
  void joinStepsFollowTheVoxelSize() {
    int[] s = BoneIsolation.joinSteps(new Vector3d(0.5, 0.7, 2));
    assertAll(
        () -> assertEquals(8, s[0]),
        () -> assertEquals(6, s[1]),
        () -> assertEquals(2, s[2]),
        () -> assertEquals(1, BoneIsolation.joinSteps(new Vector3d(1, 1, 5))[2], "at least one"));
  }

  /**
   * Timing on a forearm of 512 by 512 by 600 voxels of 0.4 by 0.4 by 0.6 mm: radius and ulna in
   * soft tissue in a cast, a table, a pad full of specks and a marker. Run with {@code
   * -Dbone.bench=true}; the slices are made on the fly and their cost is reported apart.
   */
  @Test
  @EnabledIfSystemProperty(named = "bone.bench", matches = "true")
  void timingOnALargeGrid() {
    int w = 512;
    int h = 512;
    int d = 600;
    Vector3d mm = new Vector3d(0.4, 0.4, 0.6);
    BoneExtent.SliceSource slices =
        z -> {
          float[] s = new float[w * h];
          boolean speckSlice = z % 3 == 0;
          for (int y = 0, i = 0; y < h; y++) {
            for (int x = 0; x < w; x++, i++) {
              int dx = x - 230;
              int dy = y - 240;
              int ex = x - 300;
              int ey = y - 250;
              int r2 = dx * dx + dy * dy;
              int e2 = ex * ex + ey * ey;
              float v;
              if (x >= 330 && x <= 352 && y >= 395 && y <= 400 && z >= 300 && z <= 316) {
                v = 3500; // a lead marker on the pad, 15 mm from the ulna
              } else if (x >= 328 && x <= 354 && y >= 393 && y <= 402 && z >= 298 && z <= 318) {
                v = 1000; // its partial-volume shell
              } else if (r2 <= 12 * 12 && z >= 20 && z <= 580) {
                v = 40; // radius canal
              } else if (r2 <= 30 * 30 && z >= 20 && z <= 580) {
                v = 900; // radius
              } else if (e2 <= 8 * 8 && z >= 20 && z <= 580) {
                v = 40;
              } else if (e2 <= 22 * 22 && z >= 20 && z <= 580) {
                v = 900; // ulna
              } else if (r2 <= 120 * 120 || e2 <= 110 * 110) {
                v = 40; // soft tissue
              } else if (r2 <= 150 * 150) {
                v = 1200; // plaster
              } else if (y >= 390 && y <= 470) {
                // the pad: foam denser than air, a speck of 2 voxels every 6 voxels
                v = speckSlice && y % 6 == 0 && (x % 6 == 0 || x % 6 == 1) ? 500 : -200;
              } else if (y >= 471 && y <= 480) {
                v = 350; // the table board, touching air below
              } else {
                v = -1000;
              }
              s[i] = v;
            }
          }
          return s;
        };
    long t0 = System.nanoTime();
    long sum = 0;
    for (int z = 0; z < d; z++) {
      sum += slices.slice(z).length;
    }
    long sliceMillis = (System.nanoTime() - t0) / 1_000_000;
    VolumeSpace space = VolumeSpace.ofVolume(new Vector3i(w, h, d), mm);
    Result r =
        BoneIsolation.find(space, slices, 0, mm, 300, () -> false, new WorkingMemory(1L << 30))
            .orElseThrow();
    System.out.printf(
        "BoneIsolation 512x512x600: %d ms in all, of which the slices %d ms; %d voxels kept, %d"
            + " bone, %d joined, %d dropped, %d external, %d implants, %d MB peak (%d)%n",
        r.millis(),
        sliceMillis,
        r.voxels(),
        r.bonePieces(),
        r.joinedPieces(),
        r.droppedPieces(),
        r.externalPieces(),
        r.implants(),
        r.peakBytes() >> 20,
        sum);
    assertAll(
        () -> assertEquals(2, r.bonePieces()),
        () -> assertEquals(0, r.joinedPieces()),
        () -> assertEquals(0, r.implants()),
        () -> assertEquals(0, keptInSlice(r, 0, w - 1, 390, 480, 300), "no sheet in the pad"));
  }

  private static long keptInSlice(Result r, int x0, int x1, int y0, int y1, int z) {
    long n = 0;
    for (int y = y0; y <= y1; y++) {
      for (int x = x0; x <= x1; x++) {
        if (r.region().get(x, y, z)) {
          n++;
        }
      }
    }
    return n;
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
