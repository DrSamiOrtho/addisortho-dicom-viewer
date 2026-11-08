/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.codec.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.codec.utils.DicomFileLabel.ImageDate;

class DicomFileLabelTest {

  private static Attributes header() {
    Attributes a = new Attributes();
    a.setString(Tag.SpecificCharacterSet, VR.CS, "ISO_IR 192");
    a.setString(Tag.PatientName, VR.PN, "Doe^John");
    a.setString(Tag.PatientID, VR.LO, "00123");
    a.setString(Tag.PatientAge, VR.AS, "045Y");
    a.setString(Tag.Modality, VR.CS, "CR");
    a.setString(Tag.ViewPosition, VR.CS, "AP");
    a.setString(Tag.AcquisitionDate, VR.DA, "20260115");
    a.setString(Tag.StudyDate, VR.DA, "20260110");
    return a;
  }

  private static String label(Attributes a) {
    return DicomFileLabel.imageLabel(a, true);
  }

  private static LocalDate day(String text) {
    return LocalDate.parse(text);
  }

  @Test
  void namesThePatientMrnAndDate() {
    assertEquals("John Doe MRN 00123 45Y 2026-01-15 CR AP", label(header()));
  }

  @Test
  void leadingZerosOfTheMrnAreKept() {
    assertTrue(label(header()).contains("MRN 00123"));
  }

  @Test
  void aMissingMrnIsLeftOutNotInvented() {
    Attributes a = header();
    a.remove(Tag.PatientID);
    assertEquals("John Doe 45Y 2026-01-15 CR AP", label(a));
  }

  @Test
  void theSharingNameCarriesNoIdentity() {
    assertEquals("2026-01-15 CR AP", DicomFileLabel.imageLabel(header(), false));
  }

  @Test
  void theAcquisitionDateTimeComesFirst() {
    Attributes a = header();
    a.setString(Tag.AcquisitionDateTime, VR.DT, "20260116083000.000000+0300");
    assertEquals(new ImageDate(day("2026-01-16"), true), DicomFileLabel.imageDate(a).orElseThrow());
  }

  @Test
  void aStudyDateIsLabelledAsOne() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    assertEquals("John Doe MRN 00123 45Y study 2026-01-10 CR AP", label(a));
  }

  @Test
  void anEnhancedObjectUsesTheFirstFrameTime() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    Attributes content = new Attributes();
    content.setString(Tag.FrameAcquisitionDateTime, VR.DT, "20260112101500");
    Attributes group = new Attributes();
    group.newSequence(Tag.FrameContentSequence, 1).add(content);
    Sequence groups = a.newSequence(Tag.PerFrameFunctionalGroupsSequence, 1);
    groups.add(group);
    assertEquals(new ImageDate(day("2026-01-12"), true), DicomFileLabel.imageDate(a).orElseThrow());
  }

  @Test
  void everyFrameOfAnEnhancedObjectCounts() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    Sequence groups = a.newSequence(Tag.PerFrameFunctionalGroupsSequence, 2);
    for (String time : new String[] {"20260112235900", "20260113000100"}) {
      Attributes content = new Attributes();
      content.setString(Tag.FrameAcquisitionDateTime, VR.DT, time);
      Attributes group = new Attributes();
      group.newSequence(Tag.FrameContentSequence, 1).add(content);
      groups.add(group);
    }
    assertEquals(
        "John Doe MRN 00123 2026-01-12 to 2026-01-13",
        DicomFileLabel.folderLabel("John Doe", "00123", DicomFileLabel.imageDates(a)));
  }

  @Test
  void anUnreadableAcquisitionDateFallsThroughToTheStudyDate() {
    Attributes a = header();
    a.setString(Tag.AcquisitionDate, VR.DA, "00000000");
    assertEquals(
        new ImageDate(day("2026-01-10"), false), DicomFileLabel.imageDate(a).orElseThrow());
  }

  @Test
  void noDateStaysNoDate() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    a.remove(Tag.StudyDate);
    assertTrue(DicomFileLabel.imageDate(a).isEmpty());
    assertEquals("John Doe MRN 00123 45Y CR AP", label(a));
  }

  @Test
  void keepsAnAmharicName() {
    Attributes a = header();
    a.setString(Tag.PatientName, VR.PN, "አበበ በቀለ");
    assertTrue(label(a).startsWith("አበበ በቀለ MRN 00123"), label(a));
  }

  @Test
  void aLongAmharicNameStaysWithinWhatFileSystemsAllowAndKeepsTheMrn() {
    Attributes a = header();
    a.setString(Tag.PatientName, VR.PN, "አ".repeat(64));
    a.setString(Tag.SeriesDescription, VR.LO, "ለ".repeat(60));
    a.remove(Tag.ViewPosition);
    String label = label(a);
    assertTrue(label.getBytes(StandardCharsets.UTF_8).length <= 200, label);
    assertTrue(label.contains("MRN 00123"), label);
    assertTrue(label.contains("2026-01-15"), label);
  }

  @Test
  void aHeaderCannotClimbOutOfTheFolder() {
    Attributes a = header();
    a.setString(Tag.PatientName, VR.PN, "../../etc/passwd");
    a.setString(Tag.PatientID, VR.LO, "..\\..\\x");
    String label = label(a);
    assertFalse(label.contains("/"), label);
    assertFalse(label.contains("\\"), label);
    assertFalse(label.startsWith("."), label);
  }

  @Test
  void aReservedWindowsNameIsDefused() {
    Attributes a = new Attributes();
    a.setString(Tag.PatientName, VR.PN, "CON");
    assertEquals("_CON", label(a));
  }

  @Test
  void anEmptyHeaderGivesNoLabel() {
    assertEquals("", label(new Attributes()));
    assertEquals("", label(null));
  }

  @Test
  void theContextLineSaysWhoAndWhen() {
    assertEquals(
        "John Doe  |  MRN 00123  |  2026-01-15  |  CR", DicomFileLabel.contextLine(header()));
  }

  @Test
  void theContextLineLabelsAStudyDateAndKeepsWhatIsMissingMissing() {
    Attributes a = header();
    a.remove(Tag.AcquisitionDate);
    a.remove(Tag.PatientID);
    assertEquals("John Doe  |  study 2026-01-10  |  CR", DicomFileLabel.contextLine(a));
    assertEquals("", DicomFileLabel.contextLine(new Attributes()));
    assertEquals("", DicomFileLabel.contextLine(null));
  }

  @Test
  void aFolderCarriesNameMrnAndDate() {
    assertEquals(
        "John Doe MRN 00123 2026-01-15",
        DicomFileLabel.folderLabel(
            "John Doe", "00123", List.of(new ImageDate(day("2026-01-15"), true))));
  }

  @Test
  void severalDatesBecomeARange() {
    assertEquals(
        "John Doe MRN 00123 2026-01-10 to 2026-01-15",
        DicomFileLabel.folderLabel(
            "John Doe",
            "00123",
            List.of(
                new ImageDate(day("2026-01-15"), true), new ImageDate(day("2026-01-10"), true))));
  }

  @Test
  void aMixOfDatesIsNotPassedOffAsAcquisition() {
    assertEquals(
        "John Doe MRN 00123 study 2026-01-10 to 2026-01-15",
        DicomFileLabel.folderLabel(
            "John Doe",
            "00123",
            List.of(
                new ImageDate(day("2026-01-15"), true), new ImageDate(day("2026-01-10"), false))));
  }

  @Test
  void aFolderWithNoIdentityHasNoLabel() {
    assertEquals(
        "", DicomFileLabel.folderLabel("", "", List.of(new ImageDate(day("2026-01-15"), true))));
  }
}
