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

import org.junit.jupiter.api.Test;

class BitVolumeTest {

  @Test
  void aWholeCtTakesOneBitPerVoxel() {
    // 512 x 512 x 600 = 157,286,400 voxels: 19.7 MB of bits, where a byte mask would take 157 MB.
    long bytes = BitVolume.bytesFor(512, 512, 600);

    assertEquals(512L * 512 * 600 / 8, bytes);
    // Rows start on a 64-bit word: a 37 voxel row takes one word.
    assertEquals(8L * 41 * 23, new BitVolume(37, 41, 23).bytes());
  }

  @Test
  void dilationCrossesWordBoundaries() {
    BitVolume v = new BitVolume(70, 3, 3);
    v.set(63, 1, 1);
    BitVolume out = v.emptyCopy();

    v.step(out, true, false, Thread.currentThread());

    assertEquals(7, out.count());
    assertTrue(out.get(62, 1, 1) && out.get(64, 1, 1) && out.get(63, 0, 1) && out.get(63, 1, 2));
  }

  @Test
  void dilationStopsAtTheEndOfTheRow() {
    BitVolume v = new BitVolume(64, 1, 1);
    v.set(63, 0, 0);
    BitVolume next = new BitVolume(70, 1, 1);
    next.set(69, 0, 0);

    BitVolume a = v.morph(1, true, false, null, Thread.currentThread());
    BitVolume b = next.morph(1, true, false, null, Thread.currentThread());

    assertEquals(2, a.count());
    assertEquals(2, b.count(), "no bit is set past the row");
  }

  @Test
  void erosionTreatsTheOutsideAsAsked() {
    BitVolume full = new BitVolume(5, 5, 5);
    for (int z = 0; z < 5; z++) {
      for (int y = 0; y < 5; y++) {
        for (int x = 0; x < 5; x++) {
          full.set(x, y, z);
        }
      }
    }

    BitVolume kept = full.morph(1, false, true, null, Thread.currentThread());
    BitVolume shrunk = full.morph(1, false, false, null, Thread.currentThread());

    assertEquals(125, kept.count());
    assertEquals(27, shrunk.count());
    assertFalse(shrunk.get(0, 2, 2));
  }

  @Test
  void constrainedDilationStaysInside() {
    BitVolume seed = new BitVolume(10, 1, 1);
    seed.set(0, 0, 0);
    BitVolume allowed = seed.emptyCopy();
    for (int x = 0; x < 4; x++) {
      allowed.set(x, 0, 0);
    }
    allowed.set(6, 0, 0);

    BitVolume grown = seed.morph(8, true, false, allowed, Thread.currentThread());

    assertEquals(4, grown.count());
    assertFalse(grown.get(6, 0, 0));
  }

  @Test
  void emptyOrHugeVolumesAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new BitVolume(0, 4, 4));
    assertThrows(IllegalArgumentException.class, () -> new BitVolume(65_536, 65_536, 1024));
  }
}
