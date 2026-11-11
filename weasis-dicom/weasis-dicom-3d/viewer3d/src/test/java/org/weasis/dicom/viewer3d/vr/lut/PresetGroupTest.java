/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer3d.vr.lut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Where a preset's opacity, drawn as straight lines between its points, reaches a level. */
class PresetGroupTest {

  private static PresetPoint point(int intensity, float opacity) {
    PresetPoint p = new PresetPoint();
    p.setIntensity(intensity);
    p.setOpacity(opacity);
    return p;
  }

  private static PresetGroup group(PresetPoint... points) {
    return new PresetGroup("test", points);
  }

  @Test
  void theCrossingIsReadAlongTheLineBetweenTwoPoints() {
    // AO Bone: 0 at 120, 0.4 at 200
    PresetGroup aoBone = group(point(120, 0f), point(200, 0.4f), point(300, 0.85f));
    assertEquals(200, PresetGroup.opacityCrossing(List.of(aoBone), 0.4).orElseThrow(), 1e-3);
    // CT Grayscale: a ramp from 0 at -100 to 0.875 at 500 reaches 0.4 at 174, not at its top
    PresetGroup ramp = group(point(-100, 0f), point(500, 0.875f), point(2047, 0.875f));
    assertEquals(
        -100 + 600 * 0.4 / 0.875,
        PresetGroup.opacityCrossing(List.of(ramp), 0.4).orElseThrow(),
        1e-3);
    // points in any order, and the lowest crossing of several groups
    PresetGroup shuffled = group(point(300, 0.85f), point(120, 0f), point(200, 0.4f));
    assertEquals(
        174.2857, PresetGroup.opacityCrossing(List.of(shuffled, ramp), 0.4).orElseThrow(), 1e-3);
    // already that opaque at its first point
    assertEquals(
        50, PresetGroup.opacityCrossing(List.of(group(point(50, 0.9f))), 0.4).orElseThrow(), 1e-9);
  }

  @Test
  void aPresetThatNeverGetsThereHasNoCrossing() {
    assertTrue(
        PresetGroup.opacityCrossing(List.of(group(point(0, 0f), point(900, 0.2f))), 0.4).isEmpty());
    assertTrue(PresetGroup.opacityCrossing(List.of(), 0.4).isEmpty());
  }
}
