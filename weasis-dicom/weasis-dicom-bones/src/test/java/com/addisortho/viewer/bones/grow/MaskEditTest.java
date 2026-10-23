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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MaskEditTest {

  private static final VoxelBox GRID = VoxelBox.of(40, 40, 40);

  /** A solid block as a mask. */
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

  @Test
  void brushIsAnEllipsoidClippedToTheGrid() {
    BoxMask round = MaskEdit.brush(new int[] {20, 20, 20}, new double[] {3, 3, 1.5}, GRID);
    assertTrue(round.contains(20, 20, 20));
    assertTrue(round.contains(23, 20, 20));
    assertFalse(round.contains(24, 20, 20));
    assertTrue(round.contains(20, 20, 21));
    assertFalse(round.contains(20, 20, 22), "flatter along z: thicker slices");
    assertTrue(round.contains(22, 22, 20));
    assertFalse(round.contains(23, 22, 20), "a corner of the box is outside the ellipsoid");

    BoxMask edge = MaskEdit.brush(new int[] {0, 0, 0}, new double[] {2, 2, 2}, GRID);
    assertEquals(new VoxelBox(0, 2, 0, 2, 0, 2), edge.box());
    assertNull(MaskEdit.brush(new int[] {-10, 0, 0}, new double[] {2, 2, 2}, GRID));
  }

  @Test
  void denseKeepsOnlyTheVoxelsInTheHuRange() {
    BoxMask brush = MaskEdit.brush(new int[] {10, 10, 10}, new double[] {2, 2, 2}, GRID);
    // Bone to the right of x = 10, metal at x = 12, soft tissue to the left.
    HuSource hu = (x, y, z) -> x == 12 ? 4000 : x >= 10 ? 400 : 40;
    BoxMask dense = MaskEdit.dense(brush, hu, 200, 3000);
    assertNotNull(dense);
    assertTrue(dense.contains(10, 10, 10));
    assertTrue(dense.contains(11, 10, 10));
    assertFalse(dense.contains(9, 10, 10), "soft tissue");
    assertFalse(dense.contains(12, 10, 10), "metal");
    assertNull(MaskEdit.dense(brush, (x, y, z) -> 40, 200, 3000), "nothing dense");
  }

  @Test
  void plusAddsOnlyWhatIsNew() {
    BoxMask bone = block(new VoxelBox(10, 19, 10, 19, 10, 19));
    assertSame(bone, MaskEdit.plus(bone, null));
    assertSame(bone, MaskEdit.plus(bone, block(new VoxelBox(12, 14, 12, 14, 12, 14))));
    BoxMask grown = MaskEdit.plus(bone, block(new VoxelBox(18, 22, 10, 12, 10, 12)));
    assertEquals(1000 + 3 * 3 * 3, grown.count());
    assertTrue(grown.contains(22, 12, 12));
    assertEquals(new VoxelBox(10, 22, 10, 19, 10, 19), grown.box());
  }

  @Test
  void rubbingOutWithTheBrushLeavesTheRest() {
    BoxMask bone = block(new VoxelBox(10, 19, 10, 19, 10, 19));
    BoxMask brush = MaskEdit.brush(new int[] {19, 15, 15}, new double[] {2, 2, 2}, GRID);
    BoxMask left = bone.minus(List.of(brush));
    assertFalse(left.contains(19, 15, 15));
    assertFalse(left.contains(18, 15, 15));
    assertFalse(left.contains(17, 15, 15), "the brush reaches two voxels");
    assertTrue(left.contains(16, 15, 15));
    assertTrue(left.contains(19, 10, 10), "the corner is beyond the brush");
    assertEquals(1000 - countInside(brush, bone), left.count());
  }

  private static long countInside(BoxMask brush, BoxMask bone) {
    long n = 0;
    VoxelBox b = brush.box();
    for (int z = b.z0(); z <= b.z1(); z++) {
      for (int y = b.y0(); y <= b.y1(); y++) {
        for (int x = b.x0(); x <= b.x1(); x++) {
          if (brush.contains(x, y, z) && bone.contains(x, y, z)) {
            n++;
          }
        }
      }
    }
    return n;
  }

  @Test
  void smoothingFillsANotchAndTakesOffASpike() {
    BoxMask bone = block(new VoxelBox(10, 19, 10, 19, 10, 19));
    BitVolume bits = bone.bits().copy();
    bits.clear(5, 5, 9); // a one-voxel notch in the top face
    bits.clear(5, 6, 9);
    BitVolume spiked = bits;
    BoxMask notched = new BoxMask(bone.box(), spiked);
    // A spike needs room outside the block: a mask whose box holds the spike.
    BoxMask withSpike =
        MaskEdit.plus(notched, block(new VoxelBox(14, 14, 14, 14, 20, 24))); // 1 x 1 x 5 spike

    BoxMask smooth = MaskEdit.smooth(withSpike, 1, Thread.currentThread());
    assertNotNull(smooth);
    assertTrue(smooth.contains(15, 15, 19), "the notch is filled");
    assertTrue(smooth.contains(15, 16, 19));
    assertFalse(smooth.contains(14, 14, 22), "the spike is gone");
    assertFalse(smooth.contains(14, 14, 20));
    assertTrue(smooth.contains(10, 15, 15), "the faces stay");
    assertTrue(smooth.contains(15, 15, 19));
    assertFalse(smooth.contains(10, 10, 10), "the edges and corners are rounded off");
    assertEquals(1000 - 104, smooth.count(), "the 8 corners and the 96 other edge voxels");
    assertEquals(bone.box(), smooth.box());

    BoxMask plate =
        MaskEdit.smooth(block(new VoxelBox(5, 6, 5, 30, 5, 30)), 1, Thread.currentThread());
    assertTrue(plate.contains(5, 15, 15), "a plate two voxels thick stays");
    assertTrue(plate.contains(6, 15, 15));
    assertNull(
        MaskEdit.smooth(block(new VoxelBox(5, 5, 5, 30, 5, 30)), 1, Thread.currentThread()),
        "a one-voxel sheet has too few bone neighbours everywhere");
    assertSame(bone, MaskEdit.smooth(bone, 0, Thread.currentThread()));
  }
}
