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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.vr.EditHistory.CropState;

class EditHistoryTest {

  private static final CropState FULL = new CropState(false, CropBox.FULL);

  private static CropState box(double cut) {
    return new CropState(true, CropBox.FULL.withCut(CropBox.Side.LEFT, cut));
  }

  /** A cut of the first {@code n} voxels of slice {@code z}, in a mask of {@code sizeZ} slices. */
  private static MaskDiff cutOf(int sizeZ, int z, int n) {
    MaskDiff.Builder b = new MaskDiff.Builder(sizeZ);
    b.addRun(z, 0, n);
    return b.build();
  }

  @Test
  void cropChangesUndoAndRedoManySteps() {
    AtomicReference<CropState> shown = new AtomicReference<>(FULL);
    EditHistory history = new EditHistory();
    CropState previous = FULL;
    for (int i = 1; i <= 5; i++) {
      CropState next = box(i / 10.0);
      history.push(EditHistory.crop(previous, next, shown::set));
      shown.set(next);
      previous = next;
    }
    assertEquals(5, history.undoSteps());
    for (int i = 4; i >= 0; i--) {
      assertTrue(history.undo());
      assertEquals(i == 0 ? FULL : box(i / 10.0), shown.get());
    }
    assertFalse(history.undo());
    assertEquals(5, history.redoSteps());
    assertTrue(history.redo());
    assertTrue(history.redo());
    assertEquals(box(0.2), shown.get());
  }

  @Test
  void listenersHearEveryChangeAndOutsideStepsUndoInOrder() {
    EditHistory history = new EditHistory();
    AtomicInteger heard = new AtomicInteger();
    Runnable listener = heard::incrementAndGet;
    history.addListener(listener);
    AtomicReference<String> bones = new AtomicReference<>("none");
    history.push(step(bones, "none", "femur"));
    bones.set("femur");
    history.push(step(bones, "femur", "femur and pelvis"));
    bones.set("femur and pelvis");
    assertEquals(2, heard.get());

    assertTrue(history.undo());
    assertEquals("femur", bones.get());
    assertTrue(history.redo());
    assertEquals("femur and pelvis", bones.get());
    history.clear();
    assertEquals(5, heard.get());

    history.removeListener(listener);
    history.push(step(bones, "a", "b"));
    assertEquals(5, heard.get());
  }

  /** A step done outside the view, such as a change of the selected bones. */
  private static EditHistory.Edit step(AtomicReference<String> state, String before, String after) {
    return new EditHistory.Edit() {
      @Override
      public boolean undo() {
        state.set(before);
        return true;
      }

      @Override
      public boolean redo() {
        state.set(after);
        return true;
      }
    };
  }

  @Test
  void cropsAndCutsInterleaveInOrder() {
    KeptMask mask =
        new KeptMask(VolumeSpace.ofVolume(new Vector3i(8, 8, 4), new Vector3d(1, 1, 1)));
    AtomicReference<CropState> shown = new AtomicReference<>(FULL);
    EditHistory history = new EditHistory();

    // crop, cut, crop, cut
    history.push(EditHistory.crop(FULL, box(0.1), shown::set));
    shown.set(box(0.1));
    mask.apply(cutOf(4, 0, 5));
    history.push(EditHistory.cut(mask));
    history.push(EditHistory.crop(box(0.1), box(0.3), shown::set));
    shown.set(box(0.3));
    mask.apply(cutOf(4, 1, 7));
    history.push(EditHistory.cut(mask));
    assertEquals(12, mask.removedVoxels());

    assertTrue(history.undo()); // second cut
    assertEquals(5, mask.removedVoxels());
    assertEquals(box(0.3), shown.get());
    assertTrue(history.undo()); // second crop
    assertEquals(box(0.1), shown.get());
    assertEquals(5, mask.removedVoxels());
    assertTrue(history.undo()); // first cut
    assertEquals(0, mask.removedVoxels());
    assertTrue(history.undo()); // first crop
    assertEquals(FULL, shown.get());

    assertTrue(history.redo());
    assertTrue(history.redo());
    assertEquals(box(0.1), shown.get());
    assertEquals(5, mask.removedVoxels());

    // A new step forgets what was undone after it.
    history.push(EditHistory.crop(box(0.1), box(0.5), shown::set));
    shown.set(box(0.5));
    assertFalse(history.canRedo());
    assertTrue(history.undo());
    assertTrue(history.undo());
    assertEquals(0, mask.removedVoxels());
    assertEquals(box(0.1), shown.get());
  }

  @Test
  void groupUndoesInReverseAsOneStep() {
    KeptMask mask =
        new KeptMask(VolumeSpace.ofVolume(new Vector3i(8, 8, 2), new Vector3d(1, 1, 1)));
    AtomicReference<CropState> shown = new AtomicReference<>(box(0.4));
    EditHistory history = new EditHistory();
    mask.apply(cutOf(2, 0, 3));
    history.push(EditHistory.cut(mask));

    // "Whole volume": restore the cuts and turn the crop off, as one step.
    mask.restore();
    shown.set(FULL);
    history.push(
        new EditHistory.Group()
            .add(EditHistory.cut(mask))
            .add(EditHistory.crop(box(0.4), FULL, shown::set))
            .build());
    assertTrue(mask.isFull());

    assertTrue(history.undo());
    assertEquals(3, mask.removedVoxels());
    assertEquals(box(0.4), shown.get());
    assertTrue(history.redo());
    assertTrue(mask.isFull());
    assertEquals(FULL, shown.get());
  }

  @Test
  void historyIsBoundedAndDropsStepsThatNoLongerApply() {
    EditHistory history = new EditHistory();
    AtomicReference<CropState> shown = new AtomicReference<>(FULL);
    for (int i = 0; i < EditHistory.MAX_STEPS + 10; i++) {
      history.push(EditHistory.crop(FULL, box(0.1), shown::set));
    }
    assertEquals(EditHistory.MAX_STEPS, history.undoSteps());

    history.clear();
    KeptMask mask =
        new KeptMask(VolumeSpace.ofVolume(new Vector3i(4, 4, 1), new Vector3d(1, 1, 1)));
    history.push(EditHistory.crop(FULL, box(0.2), shown::set));
    history.push(EditHistory.cut(mask)); // the mask has nothing to undo
    assertTrue(history.undo()); // the stale cut is dropped, the crop is undone
    assertEquals(FULL, shown.get());
    assertFalse(history.canUndo());
  }
}
