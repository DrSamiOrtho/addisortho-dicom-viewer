/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StoreNamingTest {
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
  void namesHoldNameAgeMrnAndLabelledImageDateNeverTheArrival() {
    Attributes a = patient("ABEBE^KEBEDE", "MRN77", "19800315", "20260101");
    String folder = StoreNaming.folderName(a, true, STUDY);
    assertEquals("KEBEDE ABEBE_45Y_MRN77_study 2026-01-01_" + StoreNaming.studyKey(STUDY), folder);
    assertEquals(
        "KEBEDE ABEBE_45Y_MRN77_study 2026-01-01_" + SOP + ".dcm",
        StoreNaming.fileName(a, true, SOP, 200));
  }

  @Test
  void theNameCanBeLeftOut() {
    Attributes a = patient("ABEBE^KEBEDE", "MRN77", "19800315", "20260101");
    assertFalse(StoreNaming.folderName(a, false, STUDY).contains("ABEBE"));
    assertEquals(
        "45Y_MRN77_study 2026-01-01_" + SOP + ".dcm", StoreNaming.fileName(a, false, SOP, 200));
  }

  @Test
  void missingFieldsFallBackToTheUids() {
    Attributes a = new Attributes();
    assertEquals(STUDY + "_" + StoreNaming.studyKey(STUDY), StoreNaming.folderName(a, true, STUDY));
    assertEquals(SOP + ".dcm", StoreNaming.fileName(a, true, SOP, 200));
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
    assertEquals(expected, StoreNaming.age(patient(null, null, birth, study)));
  }

  @Test
  void ageFallsBack() {
    Attributes noStudyDate = patient(null, null, "20000101", null);
    assertEquals("", StoreNaming.age(noStudyDate), "no image date, so no age is worked out");

    Attributes declared = new Attributes();
    declared.setString(Tag.PatientAge, VR.AS, "045Y");
    assertEquals("45Y", StoreNaming.age(declared));

    assertEquals("", StoreNaming.age(patient(null, null, "20300101", "20260101")));
    assertEquals("", StoreNaming.age(patient(null, null, "garbage", "20260101")));
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
    assertEquals(expected, StoreNaming.sanitize(raw, 48));
  }

  @Test
  void sanitizeRemovesControlAndFormatCharactersAndKeepsAmharic() {
    assertEquals("a b", StoreNaming.sanitize("a\u0000\n\tb", 48));
    assertEquals("a b c d e f g h", StoreNaming.sanitize("a<b>c:d\"e|f?g*h", 48));
    assertEquals("exe.txt", StoreNaming.sanitize("‮exe.txt", 48));
    assertEquals("አበበ ከበደ", StoreNaming.sanitize("አበበ ከበደ", 48));
    assertEquals("", StoreNaming.sanitize("../..", 48));
  }

  @Test
  void personNameUsesTheFirstNonEmptyGroup() {
    assertEquals("B A", StoreNaming.patientName(patient("A^B=C^D", null, null, null)));
    assertEquals("D C", StoreNaming.patientName(patient("=C^D", null, null, null)));
  }

  @Test
  void longNamesAreShortenedAndKeepTheUid() {
    String longUid = "1.2.840." + "1234567890.".repeat(5) + "1";
    Attributes a = patient("አ".repeat(200), "9".repeat(200), "19800315", "20260101");
    String file = StoreNaming.fileName(a, true, longUid, 200);
    assertTrue(file.endsWith("_" + longUid + ".dcm"));
    assertTrue(file.length() <= 200);
    assertTrue(file.getBytes(StandardCharsets.UTF_8).length <= 255);

    String tight = StoreNaming.fileName(a, true, longUid, longUid.length() + 4);
    assertEquals(longUid + ".dcm", tight);

    String folder = StoreNaming.folderName(a, true, STUDY);
    assertTrue(folder.length() <= 110);
    assertEquals(StoreNaming.studyKey(STUDY), StoreNaming.studyKeyOfFolder(folder).orElseThrow());
  }

  @Test
  void sopUidIsReadBackFromAFileName() {
    assertEquals(
        SOP, StoreNaming.sopUidOfFile("A B_45Y_M1_2026-11-06_" + SOP + ".dcm").orElseThrow());
    assertEquals(SOP, StoreNaming.sopUidOfFile(SOP + ".dcm").orElseThrow());
    assertTrue(StoreNaming.sopUidOfFile("notes.txt").isEmpty());
  }
}
