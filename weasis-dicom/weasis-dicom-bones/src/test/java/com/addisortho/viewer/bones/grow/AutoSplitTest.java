/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.grow.AutoSplit.Result;
import java.time.Duration;
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

  /** An L-shaped bone, 20 mm thick, its arms 10 mm wide along x and along y from (5, 5). */
  private static boolean inL(int x, int y, int z) {
    return z >= 5 && z <= 24 && x >= 5 && y >= 5 && x <= 60 && y <= 60 && (x <= 14 || y <= 14);
  }

  private static Phantom lWith(int x0, int y0, int size) {
    return new Phantom(
        70,
        70,
        30,
        (x, y, z) ->
            inL(x, y, z)
                    || (x >= x0
                        && x < x0 + size
                        && y >= y0
                        && y < y0 + size
                        && z >= 12
                        && z < 12 + size)
                ? 700
                : 0);
  }

  /**
   * A loose piece is a fragment of a bone by its distance to the bone, not to the box around the
   * bone (review of 11 Nov 2026): inside the bend of an L-shaped bone, 30 mm from either arm, a
   * piece lies inside the bone's bounding box and was joined to it; a speck there was joined too
   * instead of dropped.
   */
  @Test
  void aLoosePieceInsideABonesBoxButFarFromTheBoneIsNotItsFragment() {
    Result far = split(lWith(45, 45, 6));
    assertEquals(2, far.pieces().size(), "216 mm3, 30 mm from the bone: a bone of its own");
    assertEquals(0, far.fragments());

    Result speck = split(lWith(45, 45, 2));
    assertEquals(1, speck.pieces().size());
    assertEquals(0, speck.fragments());
    assertEquals(1, speck.specks(), "8 mm3, 30 mm from the bone: dropped");

    Result near = split(lWith(19, 30, 6));
    assertEquals(1, near.pieces().size(), "the same piece 4 mm beside an arm is its fragment");
    assertEquals(1, near.fragments());
  }

  /** Between two bones a fragment goes to the nearer one, whichever is listed first. */
  @Test
  void aFragmentGoesToTheNearerOfTwoBones() {
    Phantom two =
        new Phantom(
            80,
            40,
            40,
            (x, y, z) -> {
              boolean left = z >= 4 && z <= 35 && Phantom.inDisc(x, y, 12, 20, 8);
              boolean right = z >= 4 && z <= 35 && Phantom.inDisc(x, y, 60, 20, 9);
              // a 4 mm cube 4 mm from the left bone and 23 mm from the right one
              boolean chip = x >= 25 && x <= 28 && y >= 18 && y <= 21 && z >= 18 && z <= 21;
              return left || right || chip ? 700 : 0;
            });
    Result r = split(two);
    assertEquals(2, r.pieces().size());
    assertEquals(1, r.fragments());
    // the right bone is the larger, so it is listed first; the chip is the left one's
    assertEquals(0, r.pieces().get(0).fragments());
    assertEquals(1, r.pieces().get(1).fragments());
    assertTrue(r.pieces().get(1).mask().contains(26, 19, 19));
  }

  /**
   * A long fragment with one end 3 mm from a bone and the other end 3 mm from a larger one: equally
   * near both, it goes to the larger, whichever end the search meets first (Astra, 11 Nov 2026).
   */
  @Test
  void aFragmentEquallyNearTwoBonesGoesToTheLarger() {
    for (boolean largerOnTheLeft : new boolean[] {true, false}) {
      double big = 9;
      double small = 7;
      Phantom two =
          new Phantom(
              90,
              40,
              40,
              (x, y, z) -> {
                boolean left =
                    z >= 4
                        && z <= 35
                        && Phantom.inDisc(x, y, 12, 20, largerOnTheLeft ? big : small);
                boolean right =
                    z >= 4
                        && z <= 35
                        && Phantom.inDisc(x, y, 78, 20, largerOnTheLeft ? small : big);
                // a 3 mm thick bar across the gap, each end 3 voxels short of its bone
                int from = 12 + (int) (largerOnTheLeft ? big : small) + 4;
                int to = 78 - (int) (largerOnTheLeft ? small : big) - 4;
                boolean bar = x >= from && x <= to && y >= 19 && y <= 21 && z >= 19 && z <= 21;
                return left || right || bar ? 700 : 0;
              });
      Result r = split(two);
      assertEquals(2, r.pieces().size(), "larger on the left: " + largerOnTheLeft);
      assertEquals(1, r.fragments());
      assertEquals(1, r.pieces().get(0).fragments(), "the larger bone, listed first, has it");
    }
  }

  /**
   * A flat fragment lying 5 mm off the face of a block, so that every one of its 400 voxels on that
   * side is equally near the block, and a larger bone 5 mm from one single corner of it: the one
   * voxel that sees the larger bone decides, however many others there are (Astra, 11 Nov 2026:
   * asking only some of the nearest voxels could miss it).
   */
  @Test
  void oneVoxelOfAFlatFragmentCanSeeTheLargerBone() {
    Phantom scene =
        new Phantom(
            80,
            60,
            60,
            (x, y, z) -> {
              boolean block = x >= 4 && x <= 13 && y >= 10 && y <= 40 && z >= 10 && z <= 40;
              boolean sheet = x >= 18 && x <= 19 && y >= 15 && y <= 34 && z >= 15 && z <= 34;
              // larger than the block, its nearest voxel 5 mm beyond the sheet's last corner
              boolean large = x >= 24 && x <= 70 && y >= 34 && y <= 55 && z >= 34 && z <= 55;
              return block || sheet || large ? 700 : 0;
            });
    Result r = split(scene);
    assertEquals(2, r.pieces().size());
    assertEquals(1, r.fragments());
    assertEquals(1, r.pieces().get(0).fragments(), "the larger bone has the fragment");
    assertTrue(r.pieces().get(0).mask().contains(18, 20, 20));
  }

  /**
   * Nearly equally near is not equally near: a fragment 6 voxels from a bone along the columns and
   * 6 from a larger bone along rows that are a hair wider (1.00001 mm) goes to the nearer, smaller
   * bone. And where the columns are far finer than the rows, the bone straight along a row is still
   * found (Astra, 12 Nov 2026).
   */
  @Test
  void theNearerBoneWinsOverALargerOneAHairFurther() {
    Phantom scene =
        new Phantom(
            60,
            70,
            40,
            (x, y, z) -> {
              boolean slab = z >= 10 && z <= 29;
              boolean chip = slab && x >= 28 && x <= 31 && y >= 20 && y <= 23 && z >= 18 && z <= 21;
              boolean nearA = slab && x >= 37 && x <= 46 && y >= 13 && y <= 22; // 6 columns away
              boolean largerB = slab && x >= 10 && x <= 50 && y >= 29 && y <= 60; // 6 rows away
              return chip || nearA || largerB ? 700 : 0;
            });
    Result r = split(scene, AutoSplit.Options.of(1, 1.00001, 1));
    assertEquals(2, r.pieces().size());
    assertEquals(1, r.fragments());
    assertEquals(0, r.pieces().get(0).fragments(), "the larger bone, a hair further, has none");
    assertEquals(1, r.pieces().get(1).fragments(), "the nearer one has the chip");

    // columns of 0.05 mm against rows and slices of 1 mm
    Phantom fine =
        new Phantom(
            40,
            40,
            20,
            (x, y, z) -> {
              boolean bone = x >= 5 && x <= 35 && y >= 4 && y <= 14 && z >= 4 && z <= 15;
              boolean chip = x >= 10 && x <= 30 && y >= 18 && y <= 20 && z >= 8 && z <= 11;
              return bone || chip ? 700 : 0;
            });
    AutoSplit.Options o = AutoSplit.Options.of(0.05, 1, 1);
    Result f =
        split(
            fine,
            new AutoSplit.Options(
                o.boneHu(),
                o.denseHu(),
                o.mmX(),
                o.mmY(),
                o.mmZ(),
                1,
                o.minDepthMm(),
                o.neckRatio(),
                50, // the bone is 205 mm3, the chip 13
                o.attachMm(),
                0.5,
                o.maxWorkVoxels(),
                0,
                o.jointReachMm(),
                o.jointMaxHu(),
                o.maxJointVoxels()));
    assertEquals(1, f.pieces().size(), "the chip 4 rows from the bone is its fragment");
    assertEquals(1, f.fragments());
  }

  private static BoxMask where(Phantom p, Phantom.Shape in) {
    BitVolume bits = new BitVolume(p.sizeX, p.sizeY, p.sizeZ);
    for (int z = 0; z < p.sizeZ; z++) {
      for (int y = 0; y < p.sizeY; y++) {
        for (int x = 0; x < p.sizeX; x++) {
          if (in.hu(x, y, z) > 0) {
            bits.set(x, y, z);
          }
        }
      }
    }
    return BoxMask.trimmed(VoxelBox.of(p.sizeX, p.sizeY, p.sizeZ), bits);
  }

  private static Result twoPieces(BoxMask lower, BoxMask upper) {
    return new Result(
        List.of(
            new AutoSplit.Piece(upper, new int[] {20, 20, 30}, 0),
            new AutoSplit.Piece(lower, new int[] {20, 20, 8}, 0)),
        0,
        0,
        2,
        0);
  }

  /**
   * The border the find left four voxels off the joint is drawn again on the joint line (Sami, 12
   * Nov 2026: on a foot CT a slice of the cuboid went with the fifth metatarsal). Two bones along z
   * meet at a line of 300 HU at z 19; the pieces came apart between z 15 and 16. The narrowest band
   * cannot reach the line, the next one can, and is taken because the border then lies on it.
   */
  @Test
  void aBorderOffTheJointIsDrawnAgainOnTheJointLine() {
    Phantom joint = pressedTogether(300, 8);
    BoxMask lower = where(joint, (x, y, z) -> z <= 15 ? joint.huAt(x, y, z) : 0);
    BoxMask upper = where(joint, (x, y, z) -> z >= 16 ? joint.huAt(x, y, z) : 0);
    long voxels = lower.count() + upper.count();

    Result r =
        AutoSplit.refineBorders(
            twoPieces(lower, upper), () -> joint, AutoSplit.Options.of(1, 1, 1));

    assertEquals(2, r.pieces().size());
    assertEquals(voxels, voxels(r), "no voxel added or lost");
    BoxMask up = r.pieces().get(0).mask();
    BoxMask low = r.pieces().get(1).mask();
    assertTrue(
        low.box().z1() >= 18 && low.box().z1() <= 19, "lower bone up to z " + low.box().z1());
    assertTrue(up.box().z0() >= 19 && up.box().z0() <= 20, "upper bone from z " + up.box().z0());
    for (int z = 4; z <= 35; z++) {
      assertTrue(low.contains(20, 20, z) != up.contains(20, 20, z), "one bone at z " + z);
    }
  }

  /**
   * Where the CT shows no joint line the border moves no further than the narrowest band: a wider
   * one is taken only onto a joint. One rod of bone cut in two between z 15 and 16.
   */
  @Test
  void aBorderWithNoJointLineStaysWithinTheNarrowBand() {
    Phantom rod =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> z >= 4 && z <= 35 && Phantom.inDisc(x, y, 20, 20, 8) ? 700 : 0);
    BoxMask lower = where(rod, (x, y, z) -> z <= 15 ? rod.huAt(x, y, z) : 0);
    BoxMask upper = where(rod, (x, y, z) -> z >= 16 ? rod.huAt(x, y, z) : 0);

    Result r =
        AutoSplit.refineBorders(twoPieces(lower, upper), () -> rod, AutoSplit.Options.of(1, 1, 1));

    assertEquals(2, r.pieces().size(), "the two pieces are not joined");
    assertEquals(lower.count() + upper.count(), voxels(r));
    int top = r.pieces().get(1).mask().box().z1();
    assertTrue(top >= 13 && top <= 18, "the lower piece ends at z " + top);
  }

  /**
   * A wider band never swallows a small bone: a rod with a dark line at z 22, cut between z 15 and
   * 16. Moving the border onto the line would leave the upper piece two of its eight slices, so it
   * is refused and the border stays within the narrowest band (an elbow CT, 12 Nov 2026: a piece of
   * 4 cm3 went almost whole to its neighbour).
   */
  @Test
  void aWiderBandDoesNotSwallowASmallBone() {
    Phantom rod =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) ->
                z >= 4 && z <= 23 && Phantom.inDisc(x, y, 20, 20, 8) ? (z == 22 ? 300 : 700) : 0);
    BoxMask lower = where(rod, (x, y, z) -> z <= 15 ? rod.huAt(x, y, z) : 0);
    BoxMask upper = where(rod, (x, y, z) -> z >= 16 ? rod.huAt(x, y, z) : 0);

    Result r =
        AutoSplit.refineBorders(twoPieces(lower, upper), () -> rod, AutoSplit.Options.of(1, 1, 1));

    assertEquals(2, r.pieces().size());
    BoxMask small = r.pieces().get(1).mask();
    assertTrue(small.count() >= 0.5 * upper.count(), "the small piece keeps its body");
    assertTrue(
        small.box().z0() <= 18, "its border stayed within the narrow band: z " + small.box().z0());
  }

  /**
   * Two hollow bones along z, bright shells round dark marrow, pressed together at a line of 350 HU
   * at z 19, as one mask. Clicks in the marrow of each, where a click on a slice lands, split them
   * at the line (Sami, 12 Nov 2026: such clicks gave one side nearly everything, because everything
   * beyond the marrow was reached later than from the other click).
   */
  @Test
  void clicksInTheMarrowOfTwoBonesSplitThemAtTheJoint() throws BoneSplit.SplitException {
    Phantom hollow =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> {
              if (z < 4 || z > 35 || !Phantom.inDisc(x, y, 20, 20, 10)) {
                return 0;
              }
              if (z == 19) {
                return 350;
              }
              boolean plate = z <= 5 || z >= 34 || (z >= 17 && z <= 21);
              return plate || !Phantom.inDisc(x, y, 20, 20, 8) ? 1200 : 150;
            });
    BoxMask both = where(hollow, hollow::huAt);
    for (int[][] clicks :
        new int[][][] {
          {{20, 20, 10}, {20, 20, 28}}, // both in marrow
          {{20, 20, 10}, {20, 29, 28}}, // marrow and cortex
          {{14, 22, 7}, {25, 17, 32}}
        }) {
      BoneSplit.Split s = AutoSplit.splitBetween(both, clicks[0], clicks[1], 1, 1, 1, hollow);
      assertEquals(both.count(), s.kept().count() + s.cut().count());
      int keptTop = s.kept().box().z1();
      int cutBottom = s.cut().box().z0();
      assertTrue(keptTop >= 18 && keptTop <= 19, "the lower bone ends at z " + keptTop);
      assertTrue(cutBottom >= 19 && cutBottom <= 20, "the upper bone starts at z " + cutBottom);
    }
  }

  @Test
  void aCoarseGridStillSplitsTheJoint() {
    Result r = split(Phantom.twoBones(), AutoSplit.Options.of(1, 1, 1).withMaxWorkVoxels(10_000));
    assertTrue(r.factor() > 1);
    assertEquals(2, r.pieces().size());
  }

  /**
   * Thick slices: two bones along z with one empty 3 mm slice between them, on a grid that must be
   * coarsened. The plane is halved and the slices are kept, so the joint stays open; with one
   * factor for all three axes the empty slice shared a coarse voxel with bone and the two bones
   * came out as one (review of 11 Nov 2026).
   */
  @Test
  void aCoarseGridKeepsThickSlicesAndTheJointBetweenThem() {
    Phantom joint =
        new Phantom(
            60,
            60,
            24,
            (x, y, z) ->
                z >= 2 && z <= 21 && z != 11 && Phantom.inDisc(x, y, 30, 30, 20) ? 700 : 0);
    Result r = split(joint, AutoSplit.Options.of(1, 1, 3).withMaxWorkVoxels(20_000));
    assertTrue(r.factor() > 1);
    assertEquals(2, r.pieces().size());
  }

  @Test
  void eachAxisIsCoarsenedByItsOwnFactorTheFinestFirst() {
    // isotropic: all three alike, as one factor did
    assertArrayEquals(
        new int[] {2, 2, 2}, AutoSplit.factors(400, 400, 400, AutoSplit.Options.of(0.5, 0.5, 0.5)));
    assertArrayEquals(
        new int[] {1, 1, 1}, AutoSplit.factors(200, 200, 200, AutoSplit.Options.of(0.5, 0.5, 0.5)));
    // 1 mm pixels and 3 mm slices: the plane is halved, the slices stay
    assertArrayEquals(
        new int[] {2, 2, 1}, AutoSplit.factors(512, 512, 226, AutoSplit.Options.of(1, 1, 3)));
    // 0.4 mm pixels and 1 mm slices, a large block: the plane first, then the slices
    int[] f = AutoSplit.factors(900, 900, 600, AutoSplit.Options.of(0.4, 0.4, 1));
    assertTrue(f[0] == f[1] && f[0] >= f[2] && f[2] >= 1, java.util.Arrays.toString(f));
    assertTrue(
        (900 / f[0] + 1L) * (900 / f[1] + 1) * (600 / f[2] + 1) <= 16_000_000L * 2,
        java.util.Arrays.toString(f));
    // a grid no factor can fit still ends
    assertArrayEquals(
        new int[] {4, 4, 4},
        AutoSplit.factors(4000, 4000, 4000, AutoSplit.Options.of(0.5, 0.5, 0.5)));
  }

  /**
   * Two bones along z pressed together, as two phalanges at a joint too narrow for the scanner:
   * discs of radius 8 around (20, 20), 700 HU, z 4 to 18 and z 20 to 35, with the joint line at z
   * 19 reading {@code lineHu} over the discs of radius {@code lineRadius}, bone beyond it.
   */
  private static Phantom pressedTogether(double lineHu, double lineRadius) {
    return new Phantom(
        40,
        40,
        40,
        (x, y, z) -> {
          if (z < 4 || z > 35 || !Phantom.inDisc(x, y, 20, 20, 8)) {
            return 0;
          }
          return z == 19 && Phantom.inDisc(x, y, 20, 20, lineRadius) ? lineHu : 700;
        });
  }

  private static long voxels(Result r) {
    return r.pieces().stream().mapToLong(piece -> piece.mask().count()).sum();
  }

  @Test
  void bonesPressedTogetherSplitAtTheirJointLine() {
    Phantom joint = pressedTogether(400, 8);
    // The line is above the dense threshold: without looking for it the two bones are one.
    Result closed = split(joint, AutoSplit.Options.of(1, 1, 1).withJointHu(0));
    assertEquals(1, closed.pieces().size());
    Result r = split(joint);
    assertEquals(2, r.pieces().size());
    // The line's voxels go to one bone or the other: none is lost.
    assertEquals(voxels(closed), voxels(r));
    // The border runs along the line at z 19. The voxels of the line go either way, and the
    // watershed gives a flat face that both bones reach to the deeper one: one voxel more.
    int z1 = r.pieces().stream().mapToInt(piece -> piece.mask().box().z1()).min().orElseThrow();
    int z0 = r.pieces().stream().mapToInt(piece -> piece.mask().box().z0()).max().orElseThrow();
    assertEquals(z1 + 1, z0, "the two bones meet on one plane");
    assertTrue(z1 >= 17 && z1 <= 20, "the lower bone ends at the line, not at " + z1);
  }

  @Test
  void aCoarseGridStillSplitsAtTheJointLine() {
    Result r =
        split(pressedTogether(400, 8), AutoSplit.Options.of(1, 1, 1).withMaxWorkVoxels(2_000));
    assertTrue(r.factor() > 1);
    assertEquals(2, r.pieces().size());
  }

  @Test
  void aLineTooFaintIsNoJoint() {
    // 150 HU under the bone on each side: a streak of softer bone, not a joint.
    assertEquals(1, split(pressedTogether(550, 8)).pieces().size());
  }

  @Test
  void aDarkSheetShutInsideABoneIsNoJoint() {
    // The same line, but ending 4 mm inside the cortex all round: it does not come out.
    assertEquals(1, split(pressedTogether(400, 4)).pieces().size());
  }

  @Test
  void aNarrowMarrowCanalDoesNotSplitTheBone() {
    // A shaft along z with a canal three voxels across that reads above the dense threshold, and
    // open at both ends: a valley all round, not a sheet.
    Phantom shaft =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> {
              if (z < 2 || z > 37 || !Phantom.inDisc(x, y, 20, 20, 6)) {
                return 0;
              }
              return Phantom.inDisc(x, y, 20, 20, 1.5) ? 350 : 1200;
            });
    Result r = split(shaft);
    assertEquals(1, r.pieces().size());
    assertEquals(voxels(split(shaft, AutoSplit.Options.of(1, 1, 1).withJointHu(0))), voxels(r));
  }

  @Test
  void aWeakSpotInACortexIsNoJoint() {
    // A hollow shaft, cortex of 900 HU two voxels thick around marrow of 50 HU, with a ring of
    // 400 HU at z 19 and 20: a valley along the cortex and a ridge across it.
    Phantom shaft =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> {
              if (z < 2 || z > 37 || !Phantom.inDisc(x, y, 20, 20, 9)) {
                return 0;
              }
              if (Phantom.inDisc(x, y, 20, 20, 7)) {
                return 50;
              }
              return z == 19 || z == 20 ? 400 : 900;
            });
    assertEquals(1, split(shaft).pieces().size());
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
            AutoSplit.splitBetween(waisted(), new int[] {20, 20, 5}, new int[] {0, 0, 0}, 1, 1, 1));
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

  /**
   * Two bones pressed together over a broad face, with a fracture line of 250 HU at z 12, whose
   * border was drawn at z 15 (three voxels into the fragment). Drawn again from a click in each, it
   * moves to the line; and a click in the dark cannot let one bone take the other's body.
   */
  @Test
  void theBorderOfTwoBonesMovesToTheFractureLine() throws Exception {
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
              return z == 14 ? 250 : 1000;
            });
    BoxMask lower = block(3, 15);
    BoxMask upper = block(16, 22);
    BoneSplit.Split split =
        AutoSplit.splitBorder(
            lower, upper, new int[] {15, 15, 6}, new int[] {15, 15, 20}, 1, 1, 1, ct, 3);
    int z0 = split.cut().box().z0();
    assertTrue(z0 == 14 || z0 == 15, "the upper bone now starts at the line, not at " + z0);
    assertEquals(lower.count() + upper.count(), split.kept().count() + split.cut().count());
  }

  /**
   * Two marks in a bone that shows no cortex at all share it: a rod of 1800 voxels, all 300 HU. The
   * first version of the climb gave the first mark every voxel it looked at, which here was the
   * whole rod but the second mark (Astra, 12 Nov 2026).
   */
  @Test
  void marksInABoneWithNoCortexShareIt() throws BoneSplit.SplitException {
    Phantom rod =
        new Phantom(
            20,
            20,
            40,
            (x, y, z) -> z >= 2 && z <= 37 && Phantom.inDisc(x, y, 10, 10, 4) ? 300 : 0);
    BoxMask bone = where(rod, rod::huAt);
    assertTrue(bone.count() < 2000, "small enough for one climb to have looked at all of it");
    BoneSplit.Split s =
        AutoSplit.splitBetween(bone, new int[] {10, 10, 6}, new int[] {10, 10, 33}, 1, 1, 1, rod);
    assertEquals(bone.count(), s.kept().count() + s.cut().count());
    assertTrue(
        s.kept().count() >= 0.3 * bone.count(), "the first mark's side: " + s.kept().count());
    assertTrue(s.cut().count() >= 0.3 * bone.count(), "the second mark's side: " + s.cut().count());
  }

  /**
   * The way from a mark to cortex: a row of dim voxels with the mark's own cortex nine voxels to
   * the left and the other bone's cortex three to the right, up a slope. Left to itself the search
   * ends on the nearer, brighter cortex of the other bone; kept inside its own bone it ends on its
   * own. Nothing is given to a side by looking, a way that would cross the other side's voxels is
   * not taken, and where there is no cortex there is no way (Astra, 12 Nov 2026).
   */
  @Test
  void aMarkLooksForCortexInsideItsOwnBone() {
    int nx = 20;
    float[] g = new float[nx * 3 * 3];
    int row = (1 * 3 + 1) * nx;
    for (int x = 1; x <= 18; x++) {
      g[row + x] = 1.5f; // 200 HU
    }
    g[row + 1] = 6.5f; // 1200 HU, the mark's own bone
    g[row + 11] = 1.6f;
    g[row + 12] = 1.7f;
    g[row + 13] = 6.5f; // the other bone's
    int[] step = {1, -1, nx, -nx, nx * 3, -nx * 3};
    byte[] side = new byte[g.length];
    Thread me = Thread.currentThread();

    int[] free = AutoSplit.climb(g, side, row + 10, null, 2000, step, me);
    assertEquals(row + 13, free[0], "unbound, the search ends on the other bone's cortex");
    int[] own = AutoSplit.climb(g, side, row + 10, i -> i % nx <= 12, 2000, step, me);
    assertEquals(row + 1, own[0], "bound to its bone, on its own");
    assertEquals(row + 10, own[own.length - 1], "the way ends at the mark");
    assertEquals(10, own.length);
    assertArrayEquals(new byte[g.length], side, "looking gives nothing away");

    side[row + 5] = 2;
    assertNull(AutoSplit.climb(g, side, row + 10, i -> i % nx <= 12, 2000, step, me));
    side[row + 5] = 0;
    assertNull(AutoSplit.climb(g, side, row + 10, i -> i % nx <= 12, 4, step, me), "cap");
    g[row + 1] = 1.5f;
    assertNull(AutoSplit.climb(g, side, row + 10, i -> i % nx <= 12, 2000, step, me));
  }

  private static Result pieces(BoxMask first, BoxMask second) {
    return new Result(
        List.of(
            new AutoSplit.Piece(first, new int[] {0, 0, 0}, 0),
            new AutoSplit.Piece(second, new int[] {0, 0, 0}, 0)),
        0,
        0,
        1,
        0);
  }

  /**
   * The narrowest band may only tidy a border. A small bone that is a sheet lying on a large one,
   * within the band, with a thin dim stalk as all it has further off: flooded again, the large
   * bone's front takes the sheet before the stalk can. Until 12 Nov 2026 that was applied whatever
   * it took (Astra).
   */
  @Test
  void theNarrowestBandDoesNotGutASmallBone() {
    Phantom ct =
        new Phantom(
            30,
            30,
            30,
            (x, y, z) -> {
              boolean wide = x >= 5 && x <= 24 && y >= 5 && y <= 24;
              if (wide && z >= 3 && z <= 17) {
                return 1000;
              }
              return x >= 14 && x <= 15 && y >= 14 && y <= 15 && z >= 18 && z <= 24 ? 300 : 0;
            });
    BoxMask large = where(ct, (x, y, z) -> z <= 15 ? ct.huAt(x, y, z) : 0);
    BoxMask small = where(ct, (x, y, z) -> z >= 16 ? ct.huAt(x, y, z) : 0);

    Result r =
        AutoSplit.refineBorders(pieces(large, small), () -> ct, AutoSplit.Options.of(1, 1, 1));

    assertEquals(large.count() + small.count(), voxels(r));
    long kept = r.pieces().get(1).mask().count();
    assertTrue(kept >= AutoSplit.NARROW_KEEPS * small.count(), "the small bone keeps " + kept);
  }

  /**
   * Only bone of the two bones themselves makes a joint line. Two bones of 400 HU meet with nothing
   * between them, and a bright layer that belongs to neither lies two voxels to each side of the
   * border: bone of a third piece, or metal. Until 12 Nov 2026 any brighter CT on both sides
   * counted and this border scored as all joint (Astra).
   */
  @Test
  void brightnessThatBelongsToNeitherBoneIsNoJointLine() {
    for (double layer : new double[] {1200, 3000}) {
      Phantom ct =
          new Phantom(
              30,
              30,
              30,
              (x, y, z) -> {
                if (x < 5 || x > 24 || y < 5 || y > 24 || z < 10 || z > 21) {
                  return 0;
                }
                return z == 14 || z == 17 ? layer : 400;
              });
      BoxMask a = where(ct, (x, y, z) -> z <= 15 && z != 14 ? ct.huAt(x, y, z) : 0);
      BoxMask b = where(ct, (x, y, z) -> z >= 16 && z != 17 ? ct.huAt(x, y, z) : 0);
      double[] share = AutoSplit.jointShare(a, b, AutoSplit.Options.of(1, 1, 1), ct);
      assertEquals(400, share[1], "the faces of the border");
      assertEquals(0, share[0], "no joint line at " + layer + " HU");
    }
    // and the same layers as bone of the two bones are one
    Phantom ct = pressedTogether(300, 8);
    BoxMask lower = where(ct, (x, y, z) -> z <= 19 ? ct.huAt(x, y, z) : 0);
    BoxMask upper = where(ct, (x, y, z) -> z >= 20 ? ct.huAt(x, y, z) : 0);
    assertEquals(1, AutoSplit.jointShare(lower, upper, AutoSplit.Options.of(1, 1, 1), ct)[0]);
  }

  /**
   * An anchor the redraw took out of its bone moves to the nearest voxel inside the bone's main
   * part, not onto a small island left beside it (Astra, 12 Nov 2026).
   */
  @Test
  void anAnchorThatLeftItsBoneMovesIntoItsMainPart() {
    BoxMask bone = block(3, 15);
    int[] inside = {15, 15, 6};
    assertSame(inside, AutoSplit.anchorIn(bone, inside));
    assertArrayEquals(new int[] {15, 15, 14}, AutoSplit.anchorIn(bone, new int[] {15, 15, 19}));
    assertArrayEquals(new int[] {6, 23, 4}, AutoSplit.anchorIn(bone, new int[] {2, 27, 0}));

    // the same block with an island of eight voxels at z 18 and 19, next to where the anchor was
    BitVolume bits = new BitVolume(30, 30, 30);
    for (int z = 3; z <= 19; z++) {
      for (int y = 5; y <= 24; y++) {
        for (int x = 5; x <= 24; x++) {
          if (z <= 15 || (z >= 18 && x >= 14 && x <= 15 && y >= 14 && y <= 15)) {
            bits.set(x, y, z);
          }
        }
      }
    }
    BoxMask withIsland = BoxMask.trimmed(VoxelBox.of(30, 30, 30), bits);
    assertArrayEquals(
        new int[] {15, 15, 14}, AutoSplit.anchorIn(withIsland, new int[] {15, 15, 20}));
  }

  /**
   * Of the way from a mark to cortex only the cortex voxel starts the flood at once. The dark
   * voxels of the way are its side's but wait at their own brightness: started at once, a way
   * running beside the other bone's cortex took the voxels next to it (Astra's reread, 12 Nov
   * 2026).
   */
  @Test
  void onlyTheCortexEndOfAWayStartsAtOnce() {
    float[] g = new float[10];
    g[2] = 6.5f; // cortex
    g[3] = 1.5f;
    g[4] = 1.5f; // the mark, which the flood has given its side and queued already
    byte[] side = new byte[10];
    side[4] = 1;
    int top = 160;
    IntQueue[] buckets = new IntQueue[top + 1];

    AutoSplit.take(new int[] {2, 3, 4}, (byte) 1, g, side, buckets, top);

    assertArrayEquals(new byte[] {0, 0, 1, 1, 1, 0, 0, 0, 0, 0}, side);
    assertEquals(2, buckets[top].poll());
    assertTrue(buckets[top].isEmpty(), "nothing else at once");
    assertEquals(3, buckets[30].poll(), "the dark voxel of the way at its own brightness");
    assertTrue(buckets[30].isEmpty(), "the mark is not queued twice");
  }

  /** A block is refused before anything is allocated when it cannot fit. */
  @Test
  void aBlockTooLargeForTheMemoryIsRefused() {
    assertEquals(1000, AutoSplit.cells(10, 10, 10, 9));
    assertThrows(
        AutoSplit.TooLargeException.class, () -> AutoSplit.cells(1 << 20, 1 << 20, 1 << 20, 1));
    assertThrows(
        AutoSplit.TooLargeException.class,
        () -> AutoSplit.cells(1000, 1000, 1000, Integer.MAX_VALUE));
  }

  /**
   * A Separate that would leave a bone almost nothing is refused and the bones stay as they are. A
   * small bone lying as a sheet on a large one, all of it but a dim stalk within the band: the
   * band's answer gives the sheet to the large bone, and the clicks' answer has no joint line to
   * show for itself (an elbow CT, 12 Nov 2026: a piece of 3.5 cm3 was left 15 mm3).
   */
  @Test
  void aSeparateThatWouldGutABoneIsRefused() {
    Phantom ct =
        new Phantom(
            30,
            30,
            30,
            (x, y, z) -> {
              boolean wide = x >= 5 && x <= 24 && y >= 5 && y <= 24;
              if (wide && z >= 3 && z <= 17) {
                return 1000;
              }
              return x >= 14 && x <= 15 && y >= 14 && y <= 15 && z >= 18 && z <= 24 ? 300 : 0;
            });
    BoxMask large = where(ct, (x, y, z) -> z <= 15 ? ct.huAt(x, y, z) : 0);
    BoxMask small = where(ct, (x, y, z) -> z >= 16 ? ct.huAt(x, y, z) : 0);
    assertThrows(
        BoneSplit.SplitException.class,
        () ->
            AutoSplit.splitBorder(
                large, small, new int[] {15, 15, 6}, new int[] {14, 14, 22}, 1, 1, 1, ct, 3));
  }

  /**
   * Columns a ten-millionth of a millimetre wide: the search for the nearest bone must not walk
   * hundreds of thousands of columns that are not in the image (Astra, 12 Nov 2026).
   */
  @Test
  void theNearestBoneSearchStaysInsideTheImage() {
    Phantom fine =
        new Phantom(
            40,
            40,
            20,
            (x, y, z) -> {
              boolean bone = x >= 5 && x <= 35 && y >= 4 && y <= 14 && z >= 4 && z <= 15;
              boolean chip = x >= 10 && x <= 30 && y >= 18 && y <= 20 && z >= 8 && z <= 11;
              return bone || chip ? 700 : 0;
            });
    AutoSplit.Options o = AutoSplit.Options.of(1e-7, 1, 1);
    AutoSplit.Options tiny =
        new AutoSplit.Options(
            o.boneHu(),
            o.denseHu(),
            o.mmX(),
            o.mmY(),
            o.mmZ(),
            1,
            o.minDepthMm(),
            o.neckRatio(),
            1e-4,
            o.attachMm(),
            1e-6,
            o.maxWorkVoxels(),
            0,
            o.jointReachMm(),
            o.jointMaxHu(),
            o.maxJointVoxels());
    Result f = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> split(fine, tiny));
    assertEquals(1, f.pieces().size());
    assertEquals(1, f.fragments());

    // and rows and slices as fine: the chip is 4 columns from the bone
    Phantom across =
        new Phantom(
            40,
            40,
            20,
            (x, y, z) -> {
              boolean bone = x >= 4 && x <= 14 && y >= 5 && y <= 35 && z >= 4 && z <= 15;
              boolean chip = x >= 18 && x <= 20 && y >= 10 && y <= 30 && z >= 8 && z <= 11;
              return bone || chip ? 700 : 0;
            });
    AutoSplit.Options p = AutoSplit.Options.of(1, 1e-7, 1e-7);
    AutoSplit.Options rows =
        new AutoSplit.Options(
            p.boneHu(),
            p.denseHu(),
            p.mmX(),
            p.mmY(),
            p.mmZ(),
            1,
            p.minDepthMm(),
            p.neckRatio(),
            1e-11,
            p.attachMm(),
            1e-13,
            p.maxWorkVoxels(),
            0,
            p.jointReachMm(),
            p.jointMaxHu(),
            p.maxJointVoxels());
    Result r = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> split(across, rows));
    assertEquals(1, r.pieces().size());
    assertEquals(1, r.fragments());
  }

  /**
   * Two bones whose border the find left nine voxels off their joint, more than the band: the joint
   * line of 300 HU is at z 19, the pieces came apart between z 9 and 10. The band alone cannot
   * bring the border back, and what the upper piece keeps for certain holds bone of the lower one.
   * The two clicks alone put it on the line, and that is taken because the border then lies on a
   * joint line (a foot CT, 12 Nov 2026: 34 of 40 pairs of clicks gave the fifth metatarsal still
   * more of the cuboid).
   */
  @Test
  void twoClicksBringBackABorderFurtherOffThanTheBand() throws BoneSplit.SplitException {
    Phantom joint = pressedTogether(300, 8);
    BoxMask lower = where(joint, (x, y, z) -> z <= 9 ? joint.huAt(x, y, z) : 0);
    BoxMask upper = where(joint, (x, y, z) -> z >= 10 ? joint.huAt(x, y, z) : 0);
    BoneSplit.Split s =
        AutoSplit.splitBorder(
            lower, upper, new int[] {20, 20, 6}, new int[] {20, 20, 30}, 1, 1, 1, joint, 3);
    assertEquals(lower.count() + upper.count(), s.kept().count() + s.cut().count());
    int top = s.kept().box().z1();
    assertTrue(top >= 18 && top <= 19, "the lower bone now ends at the joint line: z " + top);
  }

  /**
   * Where the CT shows no joint line the clicks alone decide nothing: the border stays within the
   * band. One rod of 700 HU cut between z 15 and 16, clicks at z 6 and z 34, half-way z 20.
   */
  @Test
  void twoClicksWithNoJointLineMoveTheBorderOnlyWithinTheBand() throws BoneSplit.SplitException {
    Phantom rod =
        new Phantom(
            40,
            40,
            40,
            (x, y, z) -> z >= 4 && z <= 35 && Phantom.inDisc(x, y, 20, 20, 8) ? 700 : 0);
    BoxMask lower = where(rod, (x, y, z) -> z <= 15 ? rod.huAt(x, y, z) : 0);
    BoxMask upper = where(rod, (x, y, z) -> z >= 16 ? rod.huAt(x, y, z) : 0);
    BoneSplit.Split s =
        AutoSplit.splitBorder(
            lower, upper, new int[] {20, 20, 6}, new int[] {20, 20, 34}, 1, 1, 1, rod, 3);
    int top = s.kept().box().z1();
    assertTrue(top >= 12 && top <= 18, "the lower piece ends at z " + top);
  }

  /** A 20 by 20 block from z0 to z1. */
  private static BoxMask block(int z0, int z1) {
    BitVolume bits = new BitVolume(30, 30, 30);
    for (int z = z0; z <= z1; z++) {
      for (int y = 5; y <= 24; y++) {
        for (int x = 5; x <= 24; x++) {
          bits.set(x, y, z);
        }
      }
    }
    return BoxMask.trimmed(VoxelBox.of(30, 30, 30), bits);
  }
}
