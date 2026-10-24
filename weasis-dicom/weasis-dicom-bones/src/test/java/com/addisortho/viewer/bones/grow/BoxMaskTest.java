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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BoxMaskTest {

  /** A full block of the grid. */
  private static BoxMask block(int x0, int x1, int y0, int y1, int z0, int z1) {
    VoxelBox box = new VoxelBox(x0, x1, y0, y1, z0, z1);
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
  void minusTakesOutTheOverlapAndTrims() {
    BoxMask a = block(0, 9, 0, 9, 0, 9);
    BoxMask b = block(5, 14, 0, 9, 0, 9);
    BoxMask left = a.minus(List.of(b));
    assertEquals(500, left.count());
    assertEquals(new VoxelBox(0, 4, 0, 9, 0, 9), left.box());
    assertTrue(left.contains(4, 5, 5));
    assertFalse(left.contains(5, 5, 5));
    assertEquals(1000, a.count(), "the original is not changed");
  }

  @Test
  void minusWithoutOverlapIsTheSameMask() {
    BoxMask a = block(0, 4, 0, 4, 0, 4);
    assertSame(a, a.minus(List.of(block(10, 12, 0, 4, 0, 4))));
  }

  @Test
  void nothingLeftIsNull() {
    BoxMask a = block(2, 4, 2, 4, 2, 4);
    assertNull(a.minus(List.of(block(0, 9, 0, 9, 0, 9))));
    assertNull(BoxMask.trimmed(a.box(), new BitVolume(3, 3, 3)));
  }

  @Test
  void minusLocalClearsTheGivenVoxels() {
    BoxMask a = block(10, 12, 0, 0, 0, 0);
    BitVolume gone = new BitVolume(3, 1, 1);
    gone.set(0, 0, 0);
    BoxMask left = a.minusLocal(gone);
    assertEquals(new VoxelBox(11, 12, 0, 0, 0, 0), left.box());
    assertEquals(2, left.count());
  }
}
