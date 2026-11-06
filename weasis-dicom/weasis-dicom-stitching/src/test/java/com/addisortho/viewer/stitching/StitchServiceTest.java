/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class StitchServiceTest {

  private static Attributes xray(String modality) {
    Attributes a = new Attributes();
    a.setString(Tag.Modality, VR.CS, modality);
    a.setInt(Tag.Rows, VR.US, 2000);
    a.setInt(Tag.Columns, VR.US, 1600);
    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    return a;
  }

  @Test
  void acceptsSingleFramePlainXrays() {
    assertNull(StitchService.refusal(xray("CR")));
    assertNull(StitchService.refusal(xray("DX")));
    assertNotNull(StitchService.refusal(xray("CT")));
  }

  @Test
  void refusesMultiFrameColourAndStitchedImages() {
    Attributes multi = xray("DX");
    multi.setInt(Tag.NumberOfFrames, VR.IS, 3);
    assertNotNull(StitchService.refusal(multi));
    Attributes colour = xray("DX");
    colour.setInt(Tag.SamplesPerPixel, VR.US, 3);
    assertNotNull(StitchService.refusal(colour));
    Attributes machine = xray("DX");
    machine.setString(Tag.ImageType, VR.CS, "DERIVED", "PRIMARY", "STITCHED");
    assertNotNull(StitchService.refusal(machine));
    Attributes ours = xray("CR");
    ours.setString(Tag.SecondaryCaptureDeviceSoftwareVersions, VR.LO, StitchWriter.SOFTWARE);
    assertNotNull(StitchService.refusal(ours));
  }
}
