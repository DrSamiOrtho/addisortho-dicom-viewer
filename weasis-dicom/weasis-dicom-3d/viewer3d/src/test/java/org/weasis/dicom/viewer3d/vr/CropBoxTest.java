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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.weasis.dicom.viewer3d.vr.CropBox.Side;

class CropBoxTest {

  private static final double EPS = 1e-12;

  @Test
  void fullBoxCutsNothing() {
    assertAll(
        () -> assertTrue(CropBox.FULL.isFull()),
        () -> assertEquals(0.0, CropBox.FULL.minX()),
        () -> assertEquals(1.0, CropBox.FULL.maxZ()));
    for (Side side : Side.values()) {
      assertEquals(0.0, CropBox.FULL.cut(side), side.name());
    }
  }

  @Test
  void boundsAreClampedToTheVolume() {
    CropBox box = new CropBox(-0.5, 1.5, -2, 0.4, 0.2, 7);
    assertAll(
        () -> assertEquals(0.0, box.minX()),
        () -> assertEquals(1.0, box.maxX()),
        () -> assertEquals(0.0, box.minY()),
        () -> assertEquals(0.4, box.maxY()),
        () -> assertEquals(0.2, box.minZ()),
        () -> assertEquals(1.0, box.maxZ()));
  }

  @Test
  void invertedBoundsAreSwapped() {
    CropBox box = new CropBox(0.8, 0.3, 0, 1, 0, 1);
    assertEquals(0.3, box.minX(), EPS);
    assertEquals(0.8, box.maxX(), EPS);
  }

  @Test
  void notANumberFallsBackToTheWholeAxis() {
    CropBox box = new CropBox(Double.NaN, Double.NaN, 0, 1, 0, 1);
    assertEquals(0.0, box.minX());
    assertEquals(1.0, box.maxX());
  }

  @Test
  void collapsedAxisIsWidenedToTheMinimumExtent() {
    CropBox middle = new CropBox(0.5, 0.5, 0, 1, 0, 1);
    CropBox atTheEdge = new CropBox(1.0, 1.0, 0, 1, 0, 1);
    assertAll(
        () -> assertEquals(CropBox.MIN_EXTENT, middle.maxX() - middle.minX(), EPS),
        () -> assertEquals(0.5, (middle.minX() + middle.maxX()) / 2, EPS),
        () -> assertEquals(1.0, atTheEdge.maxX(), EPS),
        () -> assertEquals(1.0 - CropBox.MIN_EXTENT, atTheEdge.minX(), EPS));
  }

  @ParameterizedTest
  @EnumSource(Side.class)
  void cutRoundTripsOnEverySide(Side side) {
    CropBox box = CropBox.FULL.withCut(side, 0.25);
    assertEquals(0.25, box.cut(side), EPS);
    assertFalse(box.isFull());
    for (Side other : Side.values()) {
      if (other != side) {
        assertEquals(0.0, box.cut(other), EPS, other.name());
      }
    }
  }

  @Test
  void sidesMapToTheTextureBounds() {
    CropBox box =
        CropBox.FULL
            .withCut(Side.RIGHT, 0.1)
            .withCut(Side.LEFT, 0.2)
            .withCut(Side.FRONT, 0.3)
            .withCut(Side.BACK, 0.05)
            .withCut(Side.DOWN, 0.15)
            .withCut(Side.UP, 0.4);
    assertAll(
        () -> assertEquals(0.1, box.minX(), EPS),
        () -> assertEquals(0.8, box.maxX(), EPS),
        () -> assertEquals(0.3, box.minY(), EPS),
        () -> assertEquals(0.95, box.maxY(), EPS),
        () -> assertEquals(0.15, box.minZ(), EPS),
        () -> assertEquals(0.6, box.maxZ(), EPS));
  }

  @Test
  void cutStopsShortOfTheOppositeSide() {
    CropBox box = CropBox.FULL.withCut(Side.LEFT, 0.7).withCut(Side.RIGHT, 0.9);
    assertAll(
        () -> assertEquals(0.7, box.cut(Side.LEFT), EPS), // the opposite face did not move
        () -> assertEquals(0.3 - CropBox.MIN_EXTENT, box.cut(Side.RIGHT), EPS),
        () -> assertEquals(CropBox.MIN_EXTENT, box.maxX() - box.minX(), EPS));
  }

  @Test
  void cutOutsideTheRangeIsClamped() {
    assertAll(
        () -> assertEquals(0.0, CropBox.FULL.withCut(Side.UP, -0.3).cut(Side.UP), EPS),
        () ->
            assertEquals(
                1.0 - CropBox.MIN_EXTENT, CropBox.FULL.withCut(Side.DOWN, 4).cut(Side.DOWN), EPS),
        () -> assertEquals(0.0, CropBox.FULL.withCut(Side.FRONT, Double.NaN).cut(Side.FRONT)));
  }
}
