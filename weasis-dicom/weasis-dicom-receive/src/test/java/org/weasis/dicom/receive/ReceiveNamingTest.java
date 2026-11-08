/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReceiveNamingTest {
  private static final LocalDate ARRIVAL = LocalDate.of(2026, 11, 6);
  private static final String STUDY = "1.2.3.4";
  private static final String SOP = "1.2.3.4.5.6";

  private static Attributes patient(String name, String id, String birth, String study) {
    Attributes a = new Attributes();
    if (name != null) {
      a.setString(Tag.PatientName, VR.PN, name);
    }
    if (id != null) {
      a.setString(Tag.PatientID, VR.LO, id);
    }
    if (birth != null) {
      a.setString(Tag.PatientBirthDate, VR.DA, birth);
    }
    if (study != null) {
      a.setString(Tag.StudyDate, VR.DA, study);
    }
    return a;
  }

  @Test
  void namesHoldNameAgeMrnAndArrivalDate() {
    Attributes a = patient("ABEBE^KEBEDE", "MRN77", "19800315", "20260101");
    String folder = ReceiveNaming.folderName(a, ARRIVAL, true, STUDY);
    assertEquals("ABEBE KEBEDE_45Y_MRN77_2026-11-06_" + ReceiveNaming.studyKey(STUDY), folder);
    assertEquals(
        "ABEBE KEBEDE_45Y_MRN77_2026-11-06_" + SOP + ".dcm",
        ReceiveNaming.fileName(a, ARRIVAL, true, SOP, 200));
  }

  @Test
  void theNameCanBeLeftOut() {
    Attributes a = patient("ABEBE^KEBEDE", "MRN77", "19800315", "20260101");
    assertFalse(ReceiveNaming.folderName(a, ARRIVAL, false, STUDY).contains("ABEBE"));
    assertEquals(
        "45Y_MRN77_2026-11-06_" + SOP + ".dcm",
        ReceiveNaming.fileName(a, ARRIVAL, false, SOP, 200));
  }

  @Test
  void missingFieldsFallBackToTheUids() {
    Attributes a = new Attributes();
    assertEquals(
        "2026-11-06_" + STUDY + "_" + ReceiveNaming.studyKey(STUDY),
        ReceiveNaming.folderName(a, ARRIVAL, true, STUDY));
    assertEquals("2026-11-06_" + SOP + ".dcm", ReceiveNaming.fileName(a, ARRIVAL, true, SOP, 200));
  }

  @ParameterizedTest
  @CsvSource({
    "19800315,20260101,45Y",
    "20240101,20260101,2Y",
    "20250101,20260101,12M",
    "20251215,20260101,17D",
    "20260101,20260101,0D",
  })
  void ageIsFromBirthDateToStudyDate(String birth, String study, String expected) {
    assertEquals(expected, ReceiveNaming.age(patient(null, null, birth, study), ARRIVAL));
  }

  @Test
  void ageFallsBack() {
    Attributes noStudyDate = patient(null, null, "20000101", null);
    assertEquals("26Y", ReceiveNaming.age(noStudyDate, ARRIVAL));

    Attributes declared = new Attributes();
    declared.setString(Tag.PatientAge, VR.AS, "045Y");
    assertEquals("45Y", ReceiveNaming.age(declared, ARRIVAL));

    assertEquals("", ReceiveNaming.age(patient(null, null, "20300101", "20260101"), ARRIVAL));
    assertEquals("", ReceiveNaming.age(patient(null, null, "garbage", "20260101"), ARRIVAL));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "../../etc/passwd|etc passwd",
        "..\\\\..\\\\windows|windows",
        "name.   |name",
        "  ...name|name",
        "a..b|a.b",
        "CON|_CON",
        "nul.txt|_nul.txt",
        "COM1|_COM1",
        "LPT9 x|_LPT9 x",
        "CONTRACT|CONTRACT",
      })
  void sanitizeRemovesWhatIsUnsafe(String raw, String expected) {
    assertEquals(expected, ReceiveNaming.sanitize(raw, 48));
  }

  @Test
  void sanitizeRemovesControlAndFormatCharactersAndKeepsAmharic() {
    assertEquals("a b", ReceiveNaming.sanitize("a\u0000\n\tb", 48));
    assertEquals("a b c d e f g h", ReceiveNaming.sanitize("a<b>c:d\"e|f?g*h", 48));
    assertEquals("exe.txt", ReceiveNaming.sanitize("‮exe.txt", 48));
    assertEquals("አበበ ከበደ", ReceiveNaming.sanitize("አበበ ከበደ", 48));
    assertEquals("", ReceiveNaming.sanitize("../..", 48));
  }

  @Test
  void personNameUsesTheFirstNonEmptyGroup() {
    assertEquals("A B", ReceiveNaming.patientName(patient("A^B=C^D", null, null, null)));
    assertEquals("C D", ReceiveNaming.patientName(patient("=C^D", null, null, null)));
  }

  @Test
  void longNamesAreShortenedAndKeepTheUid() {
    String longUid = "1.2.840." + "1234567890.".repeat(5) + "1";
    Attributes a = patient("አ".repeat(200), "9".repeat(200), "19800315", "20260101");
    String file = ReceiveNaming.fileName(a, ARRIVAL, true, longUid, 200);
    assertTrue(file.endsWith("_" + longUid + ".dcm"));
    assertTrue(file.length() <= 200);
    assertTrue(file.getBytes(StandardCharsets.UTF_8).length <= 255);

    String tight = ReceiveNaming.fileName(a, ARRIVAL, true, longUid, longUid.length() + 4);
    assertEquals(longUid + ".dcm", tight);

    String folder = ReceiveNaming.folderName(a, ARRIVAL, true, STUDY);
    assertTrue(folder.length() <= 110);
    assertEquals(
        ReceiveNaming.studyKey(STUDY), ReceiveNaming.studyKeyOfFolder(folder).orElseThrow());
  }

  @Test
  void sopUidIsReadBackFromAFileName() {
    assertEquals(
        SOP, ReceiveNaming.sopUidOfFile("A B_45Y_M1_2026-11-06_" + SOP + ".dcm").orElseThrow());
    assertEquals(SOP, ReceiveNaming.sopUidOfFile(SOP + ".dcm").orElseThrow());
    assertTrue(ReceiveNaming.sopUidOfFile("notes.txt").isEmpty());
  }
}
