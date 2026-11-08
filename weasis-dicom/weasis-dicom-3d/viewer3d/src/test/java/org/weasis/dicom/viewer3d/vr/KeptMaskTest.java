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

import java.nio.ByteBuffer;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

class KeptMaskTest {

  private static VolumeSpace space(int n) {
    return VolumeSpace.ofVolume(new Vector3i(n, n, n), new Vector3d(1, 1, 1));
  }

  private static MaskDiff box(int size, int from, int to) {
    MaskDiff.Builder b = new MaskDiff.Builder(size);
    for (int z = from; z < to; z++) {
      for (int y = from; y < to; y++) {
        b.addRun(z, y * size + from, to - from);
      }
    }
    return b.build();
  }

  @Test
  void bitsFlipAcrossWordBoundariesAndExportAsBytes() {
    VoxelBits bits = new VoxelBits(100, 3, 2);
    bits.flipRange(1, 60, 170); // crosses two word boundaries of slice 1
    assertAll(
        () -> assertEquals(110, bits.countSlice(1)),
        () -> assertEquals(0, bits.countSlice(0)),
        () -> assertTrue(bits.getInSlice(1, 60)),
        () -> assertTrue(bits.getInSlice(1, 169)),
        () -> assertFalse(bits.getInSlice(1, 59)),
        () -> assertFalse(bits.getInSlice(1, 170)),
        () -> assertTrue(bits.get(60, 0, 1)),
        () -> assertTrue(bits.get(69, 1, 1)));
    bits.flipRange(1, 100, 110);
    assertEquals(100, bits.countSlice(1));
    ByteBuffer out = ByteBuffer.allocate(300);
    bits.exportSlice(1, out);
    assertEquals(300, out.position());
    assertEquals((byte) 0xFF, out.get(60));
    assertEquals((byte) 0, out.get(100));
    assertEquals((byte) 0xFF, out.get(169));
    assertEquals((byte) 0, out.get(299));
  }

  @Test
  void cutsUndoRedoAndRestore() {
    KeptMask mask = new KeptMask(space(8));
    assertTrue(mask.isFull());
    assertFalse(mask.canUndo());

    mask.apply(box(8, 1, 4));
    assertAll(
        () -> assertEquals(27, mask.removedVoxels()),
        () -> assertFalse(mask.isKept(2, 2, 2)),
        () -> assertTrue(mask.isKept(5, 5, 5)),
        () -> assertTrue(mask.canUndo()),
        () -> assertFalse(mask.canRedo()),
        () -> assertEquals(1, mask.dirtyMin()),
        () -> assertEquals(3, mask.dirtyMax()));

    mask.clearDirty();
    mask.apply(box(8, 5, 7));
    assertEquals(27 + 8, mask.removedVoxels());
    assertEquals(5, mask.dirtyMin());

    assertTrue(mask.undo());
    assertEquals(27, mask.removedVoxels());
    assertTrue(mask.isKept(5, 5, 5));
    assertTrue(mask.canRedo());

    assertTrue(mask.redo());
    assertEquals(35, mask.removedVoxels());
    assertFalse(mask.isKept(5, 5, 5));

    mask.restore();
    assertTrue(mask.isFull());
    assertTrue(mask.isKept(2, 2, 2));
    // The restore is itself undoable and brings every cut back.
    assertTrue(mask.undo());
    assertEquals(35, mask.removedVoxels());
    assertTrue(mask.redo());
    assertTrue(mask.isFull());

    mask.clearHistory();
    assertFalse(mask.canUndo());
    assertFalse(mask.canRedo());
    assertTrue(mask.isFull());
  }

  @Test
  void aNewCutDropsTheRedoHistory() {
    KeptMask mask = new KeptMask(space(8));
    mask.apply(box(8, 1, 3));
    mask.undo();
    assertTrue(mask.canRedo());
    mask.apply(box(8, 4, 6));
    assertFalse(mask.canRedo());
    assertEquals(8, mask.removedVoxels());
  }

  @Test
  void volumeVoxelsMapOntoTheGridWithTheSliceOrderReversed() {
    VolumeSpace s = space(8);
    KeptMask mask = new KeptMask(s);
    // Texture z runs the other way round: volume slice 0 is grid slice 7.
    mask.apply(box(8, 7, 8));
    assertFalse(mask.isKeptVolumeVoxel(7, 7, 0));
    assertTrue(mask.isKeptVolumeVoxel(7, 7, 7));
    Vector3i g = s.gridOfVolume(0, 0, 0, new Vector3i());
    assertEquals(new Vector3i(0, 0, 7), g);
  }

  @Test
  void runsOfASliceAreFoundWordByWord() {
    VoxelBits bits = new VoxelBits(70, 2, 1);
    bits.flipRange(0, 0, 70); // row 0 wholly set, crosses a word
    bits.flipRange(0, 100, 103);
    MaskDiff diff = MaskDiff.ofSetVoxels(bits);
    assertEquals(73, diff.voxels());
    VoxelBits other = new VoxelBits(70, 2, 1);
    diff.applyTo(other);
    assertTrue(other.get(69, 0, 0));
    assertTrue(other.get(32, 1, 0));
    assertFalse(other.get(0, 1, 0));
    assertEquals(73, other.count());
  }
}
