/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.exp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportNamingTest {
  private final ExportNaming naming = new ExportNaming(true, true);

  @TempDir Path tmp;

  private static Attributes header() {
    Attributes a = new Attributes();
    a.setString(Tag.PatientName, VR.PN, "Doe^John");
    a.setString(Tag.PatientID, VR.LO, "00123");
    a.setString(Tag.PatientAge, VR.AS, "045Y");
    a.setString(Tag.ViewPosition, VR.CS, "AP");
    a.setString(Tag.AcquisitionDate, VR.DA, "20260115");
    a.setString(Tag.StudyDate, VR.DA, "20260110");
    return a;
  }

  private String stem(Attributes a) {
    return naming.stem(a, "fallback");
  }

  @Test
  void namesThePatientMrnAgeDateAndView() {
    assertEquals("John Doe MRN 00123 45Y 2026-01-15 AP", stem(header()));
  }

  @Test
  void leavesTheIdentityOutForSharing() {
    assertEquals("2026-01-15 AP", new ExportNaming(false, false).stem(header(), "fallback"));
  }

  @Test
  void aChildsAgeKeepsItsUnit() {
    Attributes a = header();
    a.setString(Tag.PatientAge, VR.AS, "006M");
    assertEquals("John Doe MRN 00123 6M 2026-01-15 AP", stem(a));
  }

  @Test
  void worksOutTheAgeForTheDayOfTheImageNotToday() {
    Attributes a = header();
    a.remove(Tag.PatientAge);
    a.setString(Tag.PatientBirthDate, VR.DA, "19800201");
    assertEquals("John Doe MRN 00123 45Y 2026-01-15 AP", stem(a));
  }

  @Test
  void anEmptyOrMissingHeaderUsesTheFallback() {
    assertEquals("fallback", stem(new Attributes()));
    assertEquals("fallback", stem(null));
  }

  @Test
  void twoImagesOfTheSameViewGetTheirOwnFiles() {
    File first = naming.uniqueFile(tmp.toFile(), "John Doe AP", ".dcm");
    File second = naming.uniqueFile(tmp.toFile(), "John Doe AP", ".dcm");
    assertEquals("John Doe AP.dcm", first.getName());
    assertEquals("John Doe AP (2).dcm", second.getName());
  }

  @Test
  void neverReplacesAFileFromAnEarlierExport() throws IOException {
    Files.createFile(tmp.resolve("AP 2026-01-15.dcm"));
    File file = naming.uniqueFile(tmp.toFile(), "AP 2026-01-15", ".dcm");
    assertEquals("AP 2026-01-15 (2).dcm", file.getName());
  }
}
