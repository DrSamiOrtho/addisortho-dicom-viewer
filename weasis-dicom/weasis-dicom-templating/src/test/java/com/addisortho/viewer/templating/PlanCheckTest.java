/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class PlanCheckTest {

  private final Handover xray =
      new Handover("0".repeat(32), Path.of("xray.dcm"), Dicom.xray(), Instant.EPOCH, null);

  private static Attributes plan() {
    return Dicom.plan("2.25.11", "2.25.12");
  }

  private PlanCheck.Result check(Attributes plan) {
    return PlanCheck.check(Dicom.part10(plan), xray);
  }

  @Test
  void acceptsASecondaryCaptureOfTheSamePatientAndStudy() {
    PlanCheck.Result r = check(plan());
    assertTrue(r.accepted());
    assertEquals("2.25.12", r.sop());
    assertEquals("2.25.11", r.series());
  }

  @Test
  void refusesWhatIsNotAPlan() {
    assertEquals(415, PlanCheck.check(new byte[] {1, 2, 3}, xray).status());
    assertEquals(415, PlanCheck.check(Dicom.withoutMeta(plan()), xray).status());
    PlanCheck.Result itself = PlanCheck.check(Dicom.part10(Dicom.xray()), xray);
    assertEquals(415, itself.status());
    assertEquals("not a templating plan (Secondary Capture)", itself.error());
  }

  @Test
  void refusesAnotherPatientOrStudy() {
    for (int tag : new int[] {Tag.StudyInstanceUID, Tag.PatientID, Tag.IssuerOfPatientID}) {
      Attributes p = plan();
      p.setString(tag, tag == Tag.StudyInstanceUID ? VR.UI : VR.LO, "2.25.99");
      PlanCheck.Result r = check(p);
      assertEquals(422, r.status());
      assertTrue(r.error().startsWith("the plan is not for this X-ray"), r.error());
    }
    Attributes noIssuer = plan();
    noIssuer.remove(Tag.IssuerOfPatientID);
    assertEquals(422, check(noIssuer).status());
  }

  @Test
  void refusesTheXraysOwnUidsAndBadUids() {
    assertEquals(422, check(Dicom.plan("2.25.11", Dicom.SOP)).status());
    assertEquals(422, check(Dicom.plan(Dicom.SERIES, "2.25.12")).status());
    assertEquals(422, check(Dicom.plan("2.25.11", "2.25.12/../x")).status());
    assertFalse(PlanCheck.isUid("1." + "2".repeat(64)));
    assertTrue(PlanCheck.isUid("2.25.1"));
  }

  @Test
  void refusesAPlanWithoutAPicture() {
    Attributes p = plan();
    p.remove(Tag.PixelData);
    PlanCheck.Result r = check(p);
    assertEquals(422, r.status());
    assertEquals("the plan has no picture", r.error());
  }

  @Test
  void theSopClassIsSecondaryCapture() {
    assertEquals("1.2.840.10008.5.1.4.1.1.7", UID.SecondaryCaptureImageStorage);
  }
}
