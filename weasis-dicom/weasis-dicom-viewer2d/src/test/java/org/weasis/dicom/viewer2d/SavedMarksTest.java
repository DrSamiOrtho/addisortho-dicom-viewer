/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.viewer2d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

class SavedMarksTest {

  @Test
  void ownMarksAreToldApartFromOtherPresentationStates() {
    Attributes ours = new Attributes();
    SavedMarks.label(ours);
    Attributes radiology = new Attributes();
    radiology.setString(Tag.ContentLabel, VR.CS, "GSPS");

    assertTrue(SavedMarks.isOurs(ours));
    assertFalse(SavedMarks.isOurs(radiology));
    assertFalse(SavedMarks.isOurs((Attributes) null));
  }

  @Test
  void theLabelIsAValidContentLabel() {
    Attributes pr = new Attributes();
    SavedMarks.label(pr);
    String label = pr.getString(Tag.ContentLabel);

    assertTrue(label.length() <= 16 && label.matches("[A-Z0-9_]+"), label);
    assertEquals("Marks (Addis Ortho)", pr.getString(Tag.SeriesDescription));
  }
}
