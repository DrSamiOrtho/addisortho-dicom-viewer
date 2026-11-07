/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class SpacingTest {

  private static Attributes cr(String... tagValues) {
    Attributes a = new Attributes();
    a.setString(Tag.Modality, VR.CS, "CR");
    for (int i = 0; i < tagValues.length; i += 2) {
      String[] v = tagValues[i + 1].split("\\\\");
      switch (tagValues[i]) {
        case "PS" -> a.setString(Tag.PixelSpacing, VR.DS, v);
        case "IPS" -> a.setString(Tag.ImagerPixelSpacing, VR.DS, v);
        case "MAG" -> a.setString(Tag.EstimatedRadiographicMagnificationFactor, VR.DS, v);
        case "SID" -> a.setString(Tag.DistanceSourceToDetector, VR.DS, v);
        case "SOD" -> a.setString(Tag.DistanceSourceToPatient, VR.DS, v);
        case "TYPE" -> a.setString(Tag.PixelSpacingCalibrationType, VR.CS, v);
        case "MOD" -> a.setString(Tag.Modality, VR.CS, v);
        default -> throw new IllegalArgumentException(tagValues[i]);
      }
    }
    return a;
  }

  @Test
  void pixelSpacingWinsAsInTheViewer() {
    Spacing s = Spacing.weasis(cr("PS", "0.1\\0.12", "IPS", "0.15\\0.15", "MAG", "1.2"));
    assertEquals(Spacing.Basis.PIXEL_SPACING, s.basis());
    assertEquals(0.12, s.colMm(), 1e-12);
    assertEquals(0.1, s.rowMm(), 1e-12);
    assertFalse(s.square());
  }

  @Test
  void detectorSpacingIsDividedByMagnificationLikeTheViewer() {
    Spacing s = Spacing.weasis(cr("IPS", "0.15\\0.15", "MAG", "1.2"));
    assertEquals(Spacing.Basis.MAGNIFIED, s.basis());
    assertEquals(0.125, s.rowMm(), 1e-12);
    Spacing bySid = Spacing.weasis(cr("IPS", "0.15\\0.15", "SID", "1800", "SOD", "1500"));
    assertEquals(0.125, bySid.rowMm(), 1e-12);
    Spacing raw = Spacing.weasis(cr("IPS", "0.15\\0.15"));
    assertEquals(Spacing.Basis.DETECTOR, raw.basis());
    Spacing notMagModality = Spacing.weasis(cr("MOD", "OT", "IPS", "0.15\\0.15", "MAG", "1.2"));
    assertEquals(Spacing.Basis.DETECTOR, notMagModality.basis());
  }

  @Test
  void noSpacingMeansNoScaleButKeepsThePixelAspect() {
    Spacing none = Spacing.weasis(cr());
    assertFalse(none.hasScale());
    assertEquals(Spacing.UNSCALED_MM, none.colMm(), 1e-12);
    Attributes tall = cr();
    tall.setInt(Tag.PixelAspectRatio, VR.IS, 2, 1);
    Spacing t = Spacing.weasis(tall);
    assertEquals(2 * t.colMm(), t.rowMm(), 1e-12);
  }

  @Test
  void flagsWhereTheTemplatingPageWouldDisagree() {
    // the page has no SID/SOD fallback (Astra): the viewer corrects, the page does not
    Attributes sid = cr("IPS", "0.15\\0.15", "SID", "1800", "SOD", "1500");
    assertTrue(Spacing.disagreement(sid).isPresent());
    // the page ignores a magnification factor outside 0.9 to 2
    assertTrue(Spacing.disagreement(cr("IPS", "0.15\\0.15", "MAG", "2.5")).isPresent());
    // the page prefers ImagerPixelSpacing over an uncalibrated PixelSpacing equal to it
    assertTrue(
        Spacing.disagreement(cr("PS", "0.15\\0.15", "IPS", "0.15\\0.15", "MAG", "1.2"))
            .isPresent());
    assertFalse(Spacing.disagreement(cr("IPS", "0.15\\0.15", "MAG", "1.2")).isPresent());
    assertFalse(Spacing.disagreement(cr("PS", "0.1\\0.1", "TYPE", "GEOMETRY")).isPresent());
  }
}
