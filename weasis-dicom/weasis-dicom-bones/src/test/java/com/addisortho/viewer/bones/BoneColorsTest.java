/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.grow.BitVolume;
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.VoxelBox;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bones that touch never share a colour while the palette has one left. */
class BoneColorsTest {

  private static BoxMask block(int x0, int x1, int y0, int y1) {
    VoxelBox box = new VoxelBox(x0, x1, y0, y1, 0, 4);
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
  void bonesAreNearByTheirVoxelsNotTheirBoxes() {
    BoxMask a = block(0, 9, 0, 9);
    assertTrue(BoneColors.near(a, block(11, 20, 0, 9), 3), "two voxels apart");
    assertTrue(BoneColors.near(block(11, 20, 0, 9), a, 3), "either way round");
    assertFalse(BoneColors.near(a, block(14, 20, 0, 9), 3), "four voxels apart");
    assertFalse(BoneColors.near(a, block(30, 40, 30, 40), 3));
    // an L round a corner block: their boxes overlap, their voxels are far apart
    VoxelBox box = new VoxelBox(0, 40, 0, 40, 0, 4);
    BitVolume bits = new BitVolume(41, 41, 5);
    for (int z = 0; z < 5; z++) {
      for (int i = 0; i <= 40; i++) {
        bits.set(i, 0, z);
        bits.set(0, i, z);
      }
    }
    assertFalse(BoneColors.near(new BoxMask(box, bits), block(30, 36, 30, 36), 3));
  }

  private static BoxMask voxel(int x, int y, int z) {
    BitVolume bits = new BitVolume(1, 1, 1);
    bits.set(0, 0, 0);
    return new BoxMask(new VoxelBox(x, x, y, y, z, z), bits);
  }

  /** Bones that meet only corner to corner are neighbours too (Astra, 12 Nov 2026). */
  @Test
  void bonesThatMeetCornerToCornerAreNear() {
    assertTrue(BoneColors.near(voxel(0, 0, 0), voxel(1, 1, 0), 3));
    assertTrue(BoneColors.near(voxel(0, 0, 0), voxel(3, 3, 3), 3));
    assertTrue(BoneColors.near(voxel(0, 0, 0), voxel(-3, 2, -1), 3));
    assertFalse(BoneColors.near(voxel(0, 0, 0), voxel(4, 0, 0), 3));
    assertFalse(BoneColors.near(voxel(0, 0, 0), voxel(4, 4, 4), 3));
    // a block and a block off its corner, two voxels away along each axis
    assertTrue(BoneColors.near(block(0, 9, 0, 9), block(12, 20, 12, 20), 3));
  }

  /**
   * Twenty bones in a row, each touching the next, with the ninth also touching the first as the
   * eight-colour round did in a foot: no two neighbours share a colour.
   */
  @Test
  void touchingBonesNeverShareAColour() {
    List<BoxMask> row = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      row.add(block(i * 6, i * 6 + 5, 0, 5));
    }
    row.add(block(0, 53, 7, 9)); // lies along the first nine
    List<Color> colours = BoneColors.assign(row);
    for (int i = 0; i < row.size(); i++) {
      for (int j = 0; j < i; j++) {
        if (BoneColors.near(row.get(i), row.get(j), BoneColors.REACH)) {
          assertNotEquals(colours.get(i), colours.get(j), i + " and " + j + " touch");
        }
      }
    }
    // bones that touch nothing still run through the palette in turn
    List<Color> apart =
        BoneColors.assign(List.of(block(0, 2, 0, 2), block(20, 22, 0, 2), block(40, 42, 0, 2)));
    assertEquals(BoneColors.PALETTE[0], apart.get(0));
    assertEquals(BoneColors.PALETTE[1], apart.get(1));
    assertEquals(BoneColors.PALETTE[2], apart.get(2));
  }

  @Test
  void aNewBoneTakesAColourItsNeighboursDoNotHave() {
    BoxMask a = block(0, 9, 0, 9);
    Bone first = new Bone(1, "one", BoneColors.PALETTE[1], a, new int[] {0, 0, 0}, null);
    // id 2 would take the second colour in turn, which its neighbour already has
    Color c = BoneColors.pick(2, block(10, 19, 0, 9), List.of(first));
    assertNotEquals(BoneColors.PALETTE[1], c);
    assertEquals(BoneColors.PALETTE[1], BoneColors.pick(2, block(40, 49, 0, 9), List.of(first)));
  }
}
