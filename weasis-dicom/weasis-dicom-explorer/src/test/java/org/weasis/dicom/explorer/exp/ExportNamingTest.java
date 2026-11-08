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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void namesThePatientAgeViewAndDate() {
    assertEquals("John Doe 45Y AP 2026-01-15", stem(header()));
  }

  @Test
  void leavesTheNameAndAgeOutForSharing() {
    assertEquals("AP 2026-01-15", new ExportNaming(false, false).stem(header(), "fallback"));
  }

  @Test
  void aChildsAgeKeepsItsUnit() {
    Attributes a = header();
    a.setString(Tag.PatientAge, VR.AS, "006M");
    assertEquals("John Doe 6M AP 2026-01-15", stem(a));
  }

  @Test
  void worksOutTheAgeForTheDayOfTheImageNotToday() {
    Attributes a = header();
    a.remove(Tag.PatientAge);
    a.setString(Tag.PatientBirthDate, VR.DA, "19800201");
    assertEquals("John Doe 45Y AP 2026-01-15", stem(a));
  }

  @Test
  void aMissingAgeIsLeftOut() {
    Attributes a = header();
    a.remove(Tag.PatientAge);
    assertEquals("John Doe AP 2026-01-15", stem(a));
  }

  @Test
  void aStudyDateIsNotPassedOffAsTheDayOfTheImage() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    assertEquals("John Doe 45Y AP study 2026-01-10", stem(a));
  }

  @Test
  void aMissingDateStaysMissing() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    a.remove(Tag.StudyDate);
    assertEquals("John Doe 45Y AP", stem(a));
  }

  @Test
  void anUnreadableDateIsTreatedAsMissing() {
    Attributes a = header();
    a.setString(Tag.AcquisitionDate, VR.DA, "notadate");
    a.remove(Tag.StudyDate);
    assertEquals("John Doe 45Y AP", stem(a));
  }

  @Test
  void usesTheSeriesDescriptionWhenThereIsNoViewPosition() {
    Attributes a = header();
    a.remove(Tag.ViewPosition);
    a.setString(Tag.SeriesDescription, VR.LO, "PELVIS LAT");
    assertEquals("John Doe 45Y PELVIS LAT 2026-01-15", stem(a));
  }

  @Test
  void dropsCharactersAFileNameCannotHold() {
    Attributes a = header();
    a.setString(Tag.ViewPosition, VR.CS, "");
    a.setString(Tag.SeriesDescription, VR.LO, "KNEE/AP: R");
    String stem = stem(a);
    assertFalse(stem.contains("/"), stem);
    assertFalse(stem.contains(":"), stem);
  }

  @Test
  void keepsANonEnglishName() {
    Attributes a = header();
    a.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
    a.setString(Tag.PatientName, VR.PN, "አበበ");
    assertTrue(stem(a).startsWith("አበበ 45Y"), stem(a));
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
