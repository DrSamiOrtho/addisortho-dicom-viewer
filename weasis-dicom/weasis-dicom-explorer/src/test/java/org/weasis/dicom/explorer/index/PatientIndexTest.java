/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.explorer.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Made-up patients only. */
class PatientIndexTest {

  @TempDir Path dir;

  private static final long OLD = 1_700_000_000_000L;

  private static Attributes header(String name, String id, String study, String sop, String date) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, sop);
    a.setString(Tag.StudyInstanceUID, VR.UI, study);
    a.setString(Tag.SeriesInstanceUID, VR.UI, study + ".1");
    a.setString(Tag.Modality, VR.CS, "CT");
    a.setString(Tag.PatientName, VR.PN, name);
    a.setString(Tag.PatientID, VR.LO, id);
    a.setString(Tag.PatientBirthDate, VR.DA, "19800215");
    a.setString(Tag.StudyDate, VR.DA, date);
    a.setString(Tag.StudyDescription, VR.LO, "KNEE CT");
    a.setString(Tag.AccessionNumber, VR.SH, "ACC77");
    return a;
  }

  private static void writeDicom(Path file, Attributes a) throws IOException {
    Files.createDirectories(file.getParent());
    try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
      out.writeDataset(a.createFileMetaInformation(UID.ExplicitVRLittleEndian), a);
    }
    Files.setLastModifiedTime(file, FileTime.fromMillis(OLD));
  }

  private static byte[] dicomBytes(Attributes a) throws IOException {
    Path tmp = Files.createTempFile("ao", ".dcm");
    try {
      writeDicom(tmp, a);
      return Files.readAllBytes(tmp);
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static StudyEntry study(String name, String id, String date, String desc) {
    return new StudyEntry(
        "1.2." + id,
        name,
        id,
        "19800215",
        "M",
        date,
        desc,
        "ACC" + id,
        "CT",
        1,
        1,
        List.of(),
        List.of(),
        false);
  }

  @Test
  void namesMatchInAnyOrderByWordStartWithoutCaseOrAccents() {
    List<StudyEntry> all =
        List.of(
            study("TESTA^ABEBECH^K", "P100", "20261001", "KNEE"),
            study("Müller^Jürgen", "P200", "20250102", "HIP"),
            study("ፈተና^ሰው", "P300", "20240505", "SPINE"));
    assertEquals("P100", PatientMatcher.search(all, "abebech testa").getFirst().patientId());
    assertEquals("P100", PatientMatcher.search(all, "abe tes").getFirst().patientId());
    assertEquals("P200", PatientMatcher.search(all, "muller jurg").getFirst().patientId());
    assertEquals("P300", PatientMatcher.search(all, "ሰው").getFirst().patientId());
    assertTrue(PatientMatcher.search(all, "abebech zzz").isEmpty());
    assertEquals(3, PatientMatcher.search(all, "  ").size());
  }

  @Test
  void idsAccessionDatesAndDescriptionsMatch() {
    List<StudyEntry> all =
        List.of(
            study("TESTA^ONE", "P100", "20261001", "KNEE CT"),
            study("TESTB^TWO", "Q200", "20250102", "PELVIS"));
    assertEquals("P100", PatientMatcher.search(all, "p1").getFirst().patientId());
    assertEquals("Q200", PatientMatcher.search(all, "accq2").getFirst().patientId());
    assertEquals("P100", PatientMatcher.search(all, "01/10/2026").getFirst().patientId());
    assertEquals("P100", PatientMatcher.search(all, "20261001").getFirst().patientId());
    assertEquals("Q200", PatientMatcher.search(all, "2025-01-02").getFirst().patientId());
    assertEquals(2, PatientMatcher.search(all, "15.02.1980").size(), "birth date");
    assertEquals("Q200", PatientMatcher.search(all, "2025").getFirst().patientId());
    assertEquals("Q200", PatientMatcher.search(all, "pelv").getFirst().patientId());
    // newest study first
    assertEquals("P100", PatientMatcher.search(all, "").getFirst().patientId());
  }

  @Test
  void typedDatesAreReadInDayMonthOrder() {
    assertEquals("20261001", PatientMatcher.toDicomDate("1/10/2026"));
    assertEquals("20261001", PatientMatcher.toDicomDate("01-10-2026"));
    assertNull(PatientMatcher.toDicomDate("33/13/2026"));
    assertNull(PatientMatcher.toDicomDate("abebe"));
    assertEquals("TESTA ONE TWO", PatientMatcher.displayName("TESTA^ONE^TWO"));
    assertEquals("01/10/2026", PatientMatcher.displayDate("20261001"));
  }

  @Test
  void scannerFindsLooseAndZippedDicomAndSkipsTheRest() throws Exception {
    Path telegram = dir.resolve("Telegram Desktop");
    writeDicom(
        telegram.resolve("ct1.dcm"), header("TESTA^ONE", "P1", "1.2.3", "1.2.3.1", "20261001"));
    writeDicom(
        telegram.resolve("noext"), header("TESTA^ONE", "P1", "1.2.3", "1.2.3.2", "20261001"));
    Files.writeString(telegram.resolve("note.txt"), "not dicom");
    writeDicom(
        telegram.resolve(".hidden").resolve("x.dcm"),
        header("HIDDEN^X", "H", "9.9", "9.9.1", "20261001"));
    Path zip = telegram.resolve("study.zip");
    try (OutputStream os = Files.newOutputStream(zip);
        ZipOutputStream z = new ZipOutputStream(os)) {
      z.putNextEntry(new ZipEntry("DICOM/IM0001"));
      z.write(dicomBytes(header("TESTB^TWO", "P2", "4.5.6", "4.5.6.1", "20250102")));
      z.closeEntry();
      z.putNextEntry(new ZipEntry("readme.txt"));
      z.write("hello".getBytes());
      z.closeEntry();
    }
    Files.setLastModifiedTime(zip, FileTime.fromMillis(OLD));
    Files.writeString(telegram.resolve("pack.rar"), "rar");
    Files.setLastModifiedTime(telegram.resolve("pack.rar"), FileTime.fromMillis(OLD));
    // Still downloading: changed a second ago.
    Path fresh = telegram.resolve("fresh.dcm");
    writeDicom(fresh, header("TESTC^NEW", "P3", "7.8", "7.8.1", "20261002"));
    long now = OLD + 3_600_000;
    Files.setLastModifiedTime(fresh, FileTime.fromMillis(now - 1000));

    IndexScanner.Result r =
        new IndexScanner(List.of(), now, () -> false, null).scan(List.of(telegram));
    List<StudyEntry> studies = StudyEntry.of(r.files(), false);
    assertEquals(2, studies.size());
    StudyEntry one = PatientMatcher.search(studies, "testa").getFirst();
    assertEquals(2, one.imageCount());
    assertEquals(List.of(telegram.toString()), one.places());
    StudyEntry two = PatientMatcher.search(studies, "testb").getFirst();
    assertEquals(
        List.of(zip.toString()), two.containers(), "a zipped study opens through its archive");
    assertEquals(List.of(telegram.resolve("pack.rar").toString()), r.notIndexed());
    assertTrue(PatientMatcher.search(studies, "hidden").isEmpty());
    assertTrue(PatientMatcher.search(studies, "testc").isEmpty(), "a file still downloading waits");

    // Unchanged files are reused, not read again: a reused entry keeps its (fake) fields.
    IndexedFile reused =
        r.files().stream().filter(f -> f.location().endsWith("ct1.dcm")).findFirst().orElseThrow();
    IndexedFile marked =
        new IndexedFile(
            reused.location(),
            reused.size(),
            reused.modified(),
            reused.studyUid(),
            reused.seriesUid(),
            reused.sopUid(),
            "MARK",
            reused.patientName(),
            reused.patientId(),
            reused.birthDate(),
            reused.sex(),
            reused.studyDate(),
            reused.studyDescription(),
            reused.accession());
    IndexScanner.Result again =
        new IndexScanner(List.of(marked), now, () -> false, null).scan(List.of(telegram));
    assertTrue(again.files().stream().anyMatch(f -> "MARK".equals(f.modality())));
  }

  @Test
  void theSameImageInTwoPlacesCountsOnce() {
    IndexedFile a =
        new IndexedFile(
            "/a/x.dcm",
            1,
            1,
            "S",
            "SE",
            "SOP1",
            "CT",
            "N",
            "I",
            null,
            null,
            "20260101",
            null,
            null);
    IndexedFile b =
        new IndexedFile(
            "/b/x.dcm",
            1,
            1,
            "S",
            "SE",
            "SOP1",
            "CT",
            "N",
            "I",
            null,
            null,
            "20260101",
            null,
            null);
    IndexedFile c =
        new IndexedFile(
            "/b/y.dcm",
            1,
            1,
            "S",
            "SE",
            "SOP2",
            "MR",
            "N",
            "I",
            null,
            null,
            "20260101",
            null,
            null);
    StudyEntry s = StudyEntry.of(List.of(a, b, c), false).getFirst();
    assertEquals(2, s.imageCount());
    assertEquals(List.of("/a", "/b"), s.places());
    assertEquals("CT/MR", s.modalities());
  }

  @Test
  void indexAndFoldersSurviveSaveAndLoad() throws Exception {
    IndexStore store = new IndexStore(dir.resolve("data"));
    IndexedFile tricky =
        new IndexedFile(
            "C:\\Users\\x\\a\tb.dcm",
            5,
            6,
            "1.2",
            null,
            "3",
            "CT",
            "TEST^A\nB",
            "ID",
            null,
            "F",
            "20260101",
            "desc\\x",
            null);
    store.saveIndex(List.of(tricky));
    assertEquals(List.of(tricky), store.loadIndex());
    List<Path> folders = List.of(dir.resolve("Telegram Desktop"), Path.of("D:\\scans \"old\""));
    store.saveFolders(folders);
    assertEquals(folders, store.loadFolders());
    store.clearIndex();
    assertTrue(store.loadIndex().isEmpty());
    assertTrue(Files.readString(store.foldersFile()).contains("\"folders\""));
  }

  @Test
  void systemAndHiddenFoldersAreSkipped() {
    assertTrue(IndexScanner.skipped(Path.of("/Users/x/Library")));
    assertTrue(IndexScanner.skipped(Path.of("C:/Users/x/AppData")));
    assertTrue(IndexScanner.skipped(Path.of("/x/.cache")));
    assertTrue(IndexScanner.skipped(Path.of("/Applications/Viewer.app")));
    assertFalse(IndexScanner.skipped(Path.of("/Users/x/Downloads/Telegram Desktop")));
  }
}
