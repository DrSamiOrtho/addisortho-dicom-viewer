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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.grow.AutoSplit.Options;
import com.addisortho.viewer.bones.grow.AutoSplit.Piece;
import com.addisortho.viewer.bones.grow.AutoSplit.Result;
import java.util.List;
import org.junit.jupiter.api.Test;

class JointShellsTest {

  private static final Options HALF_MM = Options.of(0.5, 0.5, 0.5);

  /** Everything at or above 300 HU as one found bone, with five fragments and its anchor. */
  private static Result oneBone(Phantom p, int[] anchor) {
    BitVolume bits = new BitVolume(p.sizeX, p.sizeY, p.sizeZ);
    for (int z = 0; z < p.sizeZ; z++) {
      for (int y = 0; y < p.sizeY; y++) {
        for (int x = 0; x < p.sizeX; x++) {
          if (p.huAt(x, y, z) >= 300) {
            bits.set(x, y, z);
          }
        }
      }
    }
    BoxMask mask = BoxMask.trimmed(VoxelBox.of(p.sizeX, p.sizeY, p.sizeZ), bits);
    return new Result(List.of(new Piece(mask, anchor, 5)), 5, 2, 1, 10, true);
  }

  private static long voxels(Result r) {
    return r.pieces().stream().mapToLong(piece -> piece.mask().count()).sum();
  }

  /** A tube of cortex round marrow, radius 8, with a plate of cortex closing each end. */
  private static double tube(int x, int y, int z, int z0, int z1) {
    if (z < z0 || z > z1 || !Phantom.inDisc(x, y, 20, 20, 8)) {
      return 0;
    }
    return z == z0 || z == z1 || !Phantom.inDisc(x, y, 20, 20, 6) ? 1000 : 350;
  }

  /**
   * Two bones end to end with a joint the dense read keeps shut: 450 HU over four voxels (2 mm)
   * between two end plates of 1000 HU, too wide and too shallow for the joint lines. The shells of
   * the two face each other, so the bone is cut there, in the joint.
   */
  @Test
  void twoBonesMeetingEndToEndAreCutInTheirJoint() {
    Phantom p =
        new Phantom(
            40,
            40,
            48,
            (x, y, z) ->
                z >= 22 && z <= 25 && Phantom.inDisc(x, y, 20, 20, 8)
                    ? 450
                    : Math.max(tube(x, y, z, 4, 21), tube(x, y, z, 26, 43)));
    Result one = oneBone(p, new int[] {20, 14, 10});

    Result r = JointShells.split(one, () -> p, HALF_MM);

    assertEquals(2, r.pieces().size());
    assertEquals(voxels(one), voxels(r), "no voxel added or lost");
    Piece lower = r.pieces().stream().filter(q -> q.mask().contains(20, 14, 10)).findFirst().get();
    Piece upper = r.pieces().stream().filter(q -> q != lower).findFirst().get();
    assertTrue(
        lower.mask().box().z1() >= 21 && lower.mask().box().z1() <= 25,
        "the lower bone ends in the joint, at z " + lower.mask().box().z1());
    assertEquals(lower.mask().box().z1() + 1, upper.mask().box().z0(), "they meet on one plane");
    // The bone that kept the anchor keeps the fragments counted with it; the other starts clean,
    // with an anchor of its own inside it.
    assertEquals(5, lower.fragments());
    assertEquals(0, upper.fragments());
    int[] a = upper.anchor();
    assertTrue(upper.mask().contains(a[0], a[1], a[2]));
    assertEquals(5, r.fragments());
    assertEquals(2, r.specks());
    assertTrue(r.jointsSkipped(), "what the find said is carried on");
    assertTrue(r.pieces().get(0).mask().count() >= r.pieces().get(1).mask().count());
  }

  /** One long bone has one shell: nothing is cut and the result comes back as it was. */
  @Test
  void oneBoneIsLeftAlone() {
    Phantom p = new Phantom(40, 40, 48, (x, y, z) -> tube(x, y, z, 4, 43));
    Result one = oneBone(p, new int[] {20, 14, 10});
    assertSame(one, JointShells.split(one, () -> p, HALF_MM));
  }

  /**
   * A flat bone is two tables of cortex over a soft middle, as an iliac wing or a skull: the tables
   * face each other all over, but the face a cut between them would share is as large as the bone,
   * so it is not cut. The same holds for a long bone along its length (the ulna of an elbow CT, 12
   * Nov 2026).
   */
  @Test
  void theTwoTablesOfAFlatBoneAreNotCutApart() {
    Phantom p =
        new Phantom(
            48,
            48,
            16,
            (x, y, z) ->
                x < 4 || x > 43 || y < 4 || y > 43 || z < 4 || z > 11
                    ? 0
                    : z <= 5 || z >= 10 ? 1000 : 350);
    Result one = oneBone(p, new int[] {20, 20, 4});
    assertSame(one, JointShells.split(one, () -> p, HALF_MM));
    BoxMask whole = one.pieces().get(0).mask();
    BoxMask top = AutoSplit.within(whole, new VoxelBox(0, 47, 0, 47, 8, 15));
    BoxMask bottom = AutoSplit.within(whole, new VoxelBox(0, 47, 0, 47, 0, 7));
    assertFalse(JointShells.isJoint(bottom, top, HALF_MM));
  }

  /** A nub too small to be a bone stays with the bone it sits on, joint or not. */
  @Test
  void aPartTooSmallToBeABoneIsNotCutOff() {
    Phantom p =
        new Phantom(
            40,
            40,
            48,
            (x, y, z) -> {
              if (z >= 22 && z <= 25 && Phantom.inDisc(x, y, 20, 20, 4)) {
                return 450;
              }
              // 6 voxels of a rod of radius 4: about 38 mm3 with its share of the joint.
              boolean nub = z >= 26 && z <= 31 && Phantom.inDisc(x, y, 20, 20, 4);
              return nub ? 1000 : tube(x, y, z, 4, 21);
            });
    Result one = oneBone(p, new int[] {20, 14, 10});
    assertSame(one, JointShells.split(one, () -> p, HALF_MM));
  }

  @Test
  void theSharedFaceIsMeasuredOnVoxelFaces() {
    BitVolume a = new BitVolume(4, 4, 2);
    BitVolume b = new BitVolume(4, 4, 2);
    for (int z = 0; z < 2; z++) {
      for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
          a.set(x, y, z);
          b.set(x, y, z);
        }
      }
    }
    BoxMask lower = new BoxMask(new VoxelBox(0, 3, 0, 3, 0, 1), a);
    BoxMask upper = new BoxMask(new VoxelBox(0, 3, 0, 3, 2, 3), b);
    // Sixteen faces across the slices, each 0.5 by 0.4 mm.
    assertEquals(3.2, JointShells.sharedFaceMm2(lower, upper, Options.of(0.5, 0.4, 3)), 1e-9);
    BoxMask apart = new BoxMask(new VoxelBox(0, 3, 0, 3, 3, 4), b);
    assertEquals(0, JointShells.sharedFaceMm2(lower, apart, Options.of(0.5, 0.4, 3)), 1e-9);
  }
}
