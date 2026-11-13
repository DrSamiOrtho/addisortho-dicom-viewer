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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.grow.AutoSplit.Options;
import com.addisortho.viewer.bones.grow.AutoSplit.Piece;
import com.addisortho.viewer.bones.grow.AutoSplit.Result;
import com.addisortho.viewer.bones.grow.AutoSplit.Shown;
import java.util.List;
import org.junit.jupiter.api.Test;

class WeakBoneTest {

  private static final VoxelBox GRID = VoxelBox.of(60, 40, 12);
  private static final Options MM = Options.of(1, 1, 1);

  private static BoxMask block(VoxelBox box) {
    BitVolume bits = new BitVolume(box.sizeX(), box.sizeY(), box.sizeZ());
    for (int z = 0; z < box.sizeZ(); z++) {
      for (int y = 0; y < box.sizeY(); y++) {
        for (int x = 0; x < box.sizeX(); x++) {
          bits.set(x, y, z);
        }
      }
    }
    return new BoxMask(box, bits);
  }

  private static Result found(BoxMask... masks) {
    List<Piece> pieces =
        java.util.Arrays.stream(masks)
            .map(m -> new Piece(m, new int[] {m.box().x0(), m.box().y0(), m.box().z0()}, 0))
            .toList();
    return new Result(pieces, 0, 0, 1, 0);
  }

  @Test
  void aBoneTakesTheWeakBoneBesideItButNoFurtherThanItsReach() {
    // Dense bone at x 5 to 14; a weak plate runs on from it to x 49, in the rows 18 to 21.
    BoxMask bone = block(new VoxelBox(5, 14, 10, 29, 2, 9));
    HuSource hu =
        (x, y, z) -> {
          if (bone.contains(x, y, z)) {
            return 600;
          }
          return x >= 15 && x <= 49 && y >= 18 && y <= 21 && z >= 4 && z <= 7 ? 160 : 40;
        };
    Result grown = WeakBone.grow(found(bone), GRID, () -> hu, Shown.ALL, MM);
    BoxMask mask = grown.pieces().getFirst().mask();
    assertTrue(mask.contains(10, 20, 5), "the bone keeps its own voxels");
    assertTrue(mask.contains(15, 19, 5), "the weak plate beside it is taken in");
    assertTrue(mask.contains(34, 19, 5), "up to 20 mm");
    assertFalse(mask.contains(36, 19, 5), "and no further");
    assertFalse(mask.contains(15, 25, 5), "soft tissue beside the bone is not bone");
    assertEquals(bone.count() + 20L * 4 * 4, mask.count());
  }

  @Test
  void whatTheViewDoesNotShowIsNotTaken() {
    BoxMask bone = block(new VoxelBox(5, 14, 10, 29, 2, 9));
    HuSource hu = (x, y, z) -> bone.contains(x, y, z) ? 600 : 170;
    Shown shown = (x, y, z) -> x <= 16;
    BoxMask mask = WeakBone.grow(found(bone), GRID, () -> hu, shown, MM).pieces().getFirst().mask();
    assertTrue(mask.contains(16, 20, 5));
    assertFalse(mask.contains(17, 20, 5), "cut away in the 3D view");
  }

  @Test
  void twoBonesShareTheWeakBoneBetweenThemAndStayTwo() {
    BoxMask left = block(new VoxelBox(5, 14, 10, 29, 2, 9));
    BoxMask right = block(new VoxelBox(21, 30, 10, 29, 2, 9));
    // The six voxels between them read as weak bone: a joint line pressed shut.
    HuSource hu =
        (x, y, z) ->
            left.contains(x, y, z) || right.contains(x, y, z)
                ? 600
                : x >= 15 && x <= 20 && y >= 10 && y <= 29 && z >= 2 && z <= 9 ? 180 : 40;
    Result grown = WeakBone.grow(found(left, right), GRID, () -> hu, Shown.ALL, MM);
    BoxMask a = grown.pieces().get(0).mask();
    BoxMask b = grown.pieces().get(1).mask();
    assertEquals(2, grown.pieces().size());
    assertTrue(a.contains(17, 20, 5) && !b.contains(17, 20, 5), "the near half goes to the left");
    assertTrue(b.contains(18, 20, 5) && !a.contains(18, 20, 5), "the far half to the right");
    for (int x = 5; x <= 30; x++) {
      assertTrue(
          a.contains(x, 20, 5) != b.contains(x, 20, 5), "every voxel is one bone's, at x " + x);
    }
  }

  @Test
  void aWeakShellIsClosedAndItsMarrowFilled() {
    // A ring of dense bone open on one side, the opening closed by weak bone only: a femoral head
    // with a thin weak cortex. The marrow inside reads as fat.
    BitVolume bits = new BitVolume(20, 20, 6);
    for (int z = 0; z < 6; z++) {
      for (int y = 0; y < 20; y++) {
        for (int x = 0; x < 20; x++) {
          boolean wall = x < 2 || y < 2 || y > 17;
          if (wall) {
            bits.set(x, y, z);
          }
        }
      }
    }
    BoxMask ring = BoxMask.trimmed(new VoxelBox(10, 29, 10, 29, 3, 8), bits);
    HuSource hu =
        (x, y, z) -> {
          if (ring.contains(x, y, z)) {
            return 500;
          }
          boolean weakWall = x >= 28 && x <= 29 && y >= 12 && y <= 27 && z >= 3 && z <= 8;
          return weakWall ? 160 : -60;
        };
    BoxMask mask =
        WeakBone.grow(found(ring), GRID, () -> hu, Shown.ALL, MM).pieces().getFirst().mask();
    assertTrue(mask.contains(28, 20, 5), "the weak wall closes the ring");
    assertTrue(mask.contains(20, 20, 5), "and the marrow inside is filled");
    assertFalse(mask.contains(32, 20, 5));
  }

  @Test
  void thickSlicesAreSteppedAcrossOnlyAsFarAsTheReach() {
    // 8 mm slices: 20 mm reaches two slices up, not three.
    Options thick = Options.of(1, 1, 8);
    BoxMask bone = block(new VoxelBox(5, 14, 10, 29, 4, 5));
    HuSource hu = (x, y, z) -> bone.contains(x, y, z) ? 600 : x >= 5 && x <= 14 ? 170 : 40;
    Shown shown = (x, y, z) -> y >= 10 && y <= 29;
    BoxMask mask =
        WeakBone.grow(found(bone), GRID, () -> hu, shown, thick).pieces().getFirst().mask();
    assertTrue(mask.contains(10, 20, 7), "two slices up is 16 mm away");
    assertFalse(mask.contains(10, 20, 8), "three slices up is 24 mm away");
  }
}
