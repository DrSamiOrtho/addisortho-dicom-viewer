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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.bones.GrowSession.Change;
import com.addisortho.viewer.bones.seg.SegmentationBuilder.Segment;
import java.awt.Color;
import java.util.List;
import org.junit.jupiter.api.Test;

class GrowSessionTest {

  private static Segment segment(int n) {
    return new Segment(n, "Bone " + n, Color.WHITE, null, null);
  }

  private static void grow(GrowSession s, int n) {
    s.written(s.with(segment(n)), "sop" + n, Change.GROWN);
  }

  @Test
  void undoAndRedoManySegments() {
    GrowSession s = new GrowSession("ct");
    grow(s, 1);
    grow(s, 2);
    grow(s, 3);
    assertEquals(4, s.nextSegmentNumber());

    s.written(s.withoutLast(), "u1", Change.UNDONE);
    s.written(s.withoutLast(), "u2", Change.UNDONE);
    assertEquals(List.of(segment(1)), s.segments());
    assertTrue(s.canRedo());

    s.written(s.withRedone(), "r1", Change.REDONE);
    assertEquals(List.of(segment(1), segment(2)), s.segments());
    s.written(s.withRedone(), "r2", Change.REDONE);
    assertEquals(List.of(segment(1), segment(2), segment(3)), s.segments());
    assertFalse(s.canRedo());
    assertEquals(s.segments(), s.withRedone());
  }

  @Test
  void growingForgetsWhatWasUndone() {
    GrowSession s = new GrowSession("ct");
    grow(s, 1);
    grow(s, 2);
    s.written(s.withoutLast(), "u", Change.UNDONE);
    assertTrue(s.canRedo());
    grow(s, s.nextSegmentNumber());
    assertFalse(s.canRedo());
    assertEquals(2, s.segments().size());
  }

  @Test
  void undoingEverythingLeavesNoSegment() {
    GrowSession s = new GrowSession("ct");
    grow(s, 1);
    s.written(s.withoutLast(), null, Change.UNDONE);
    assertFalse(s.canUndo());
    assertTrue(s.canRedo());
    s.written(s.withoutLast(), null, Change.UNDONE); // nothing more to undo
    s.written(s.withRedone(), "r", Change.REDONE);
    assertEquals(List.of(segment(1)), s.segments());
  }
}
