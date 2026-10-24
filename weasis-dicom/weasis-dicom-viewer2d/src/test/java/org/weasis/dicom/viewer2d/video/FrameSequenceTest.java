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
package org.weasis.dicom.viewer2d.video;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FrameSequenceTest {

  @Test
  void fullTurnLoopsWithoutRepeatingTheFirstView() {
    double[] a = FrameSequence.rotationAngles(360, 4);
    assertArrayEquals(new double[] {0, 90, 180, 270}, a, 1e-9);
    double[] b = FrameSequence.rotationAngles(-720, 8);
    assertEquals(-630, b[7], 1e-9);
  }

  @Test
  void partialTurnShowsBothEnds() {
    assertArrayEquals(new double[] {0, 45, 90}, FrameSequence.rotationAngles(90, 3), 1e-9);
    assertArrayEquals(new double[] {0, -90}, FrameSequence.rotationAngles(-90, 2), 1e-9);
    assertArrayEquals(new double[] {0}, FrameSequence.rotationAngles(180, 1), 1e-9);
  }

  @Test
  void stepsAddUpToTheAngles() {
    double[] steps = FrameSequence.rotationSteps(360, 36);
    assertEquals(0, steps[0], 1e-12);
    double sum = Arrays.stream(steps).sum();
    assertEquals(350, sum, 1e-9);
    double[] partial = FrameSequence.rotationSteps(120, 5);
    assertArrayEquals(new double[] {0, 30, 30, 30, 30}, partial, 1e-9);
  }

  @Test
  void slicesRunEitherWayAndEndOnTheLast() {
    assertArrayEquals(new int[] {0, 1, 2, 3}, FrameSequence.sliceIndices(0, 3, 1));
    assertArrayEquals(new int[] {9, 7, 5}, FrameSequence.sliceIndices(9, 5, 2));
    assertArrayEquals(new int[] {0, 3, 6, 7}, FrameSequence.sliceIndices(0, 7, 3));
    assertArrayEquals(new int[] {4}, FrameSequence.sliceIndices(4, 4, 5));
  }

  @Test
  void rejectsWhatCannotMakeAVideo() {
    assertThrows(IllegalArgumentException.class, () -> FrameSequence.rotationAngles(360, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> FrameSequence.rotationAngles(360, FrameSequence.MAX_FRAMES + 1));
    assertThrows(IllegalArgumentException.class, () -> FrameSequence.rotationAngles(Double.NaN, 3));
    assertThrows(IllegalArgumentException.class, () -> FrameSequence.sliceIndices(0, 3, 0));
    assertThrows(IllegalArgumentException.class, () -> FrameSequence.durationSeconds(10, 0));
  }

  @Test
  void durationFollowsTheFrameRate() {
    assertEquals(2.4, FrameSequence.durationSeconds(72, 30), 1e-9);
  }
}
