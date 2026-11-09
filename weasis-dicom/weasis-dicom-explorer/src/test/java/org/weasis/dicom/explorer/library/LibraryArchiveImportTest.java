/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real archives, written and read back, through the same store a normal import uses. */
class LibraryArchiveImportTest {
  private static final String STUDY_A = "1.2.826.0.1.3680043.8.498.20";
  private static final String STUDY_B = "1.2.826.0.1.3680043.8.498.21";
  private static final String SERIES = "1.2.826.0.1.3680043.8.498.22";

  @TempDir Path tmp;
  private Path root;
  private StudyStore store;
  private StudyCatalog catalog;

  @BeforeEach
  void openStore() throws IOException {
    root = tmp.resolve("library");
    store = open();
  }

  private StudyStore open() throws IOException {
    StudyStore opened = new StudyStore(root, true);
    catalog = new StudyCatalog(root.resolve(".catalog/studies.tsv"));
    catalog.load();
    opened.attachCatalog(catalog);
    opened.prepare();
    return opened;
  }

  private static Attributes image(String study, String sop, String id, byte pixel) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, UID.CTImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, sop);
    a.setString(Tag.StudyInstanceUID, VR.UI, study);
    a.setString(Tag.SeriesInstanceUID, VR.UI, SERIES);
    a.setString(Tag.PatientName, VR.PN, "Synthetic^Test");
    a.setString(Tag.PatientID, VR.LO, id);
    a.setString(Tag.Modality, VR.CS, "CT");
    a.setString(Tag.AcquisitionDate, VR.DA, "20260115");
    a.setBytes(Tag.PixelData, VR.OB, new byte[] {pixel, 2, 3, 4});
    return a;
  }

  private static Attributes multiframe(String sop) {
    Attributes a = image(STUDY_A, sop, "001", (byte) 0);
    a.setInt(Tag.Rows, VR.US, 2);
    a.setInt(Tag.Columns, VR.US, 2);
    a.setInt(Tag.BitsAllocated, VR.US, 8);
    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    a.setString(Tag.NumberOfFrames, VR.IS, "3");
    a.setBytes(Tag.PixelData, VR.OB, new byte[12]);
    return a;
  }

  private static byte[] bytes(Attributes a) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DicomOutputStream dicom = new DicomOutputStream(out, UID.ExplicitVRLittleEndian)) {
      dicom.writeDataset(a.createFileMetaInformation(UID.ExplicitVRLittleEndian), a);
    }
    return out.toByteArray();
  }

  private Path zip(String name, Map<String, byte[]> entries) throws IOException {
    Path file = tmp.resolve(name);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(e.getKey()));
        out.write(e.getValue());
        out.closeEntry();
      }
    }
    return file;
  }

  private LibraryImport.Report importing(Path source) throws IOException {
    return importing(source, store, () -> false);
  }

  private static LibraryImport.Report importing(
      Path source, StudyStore into, BooleanSupplier cancelled) throws IOException {
    return new LibraryImport(into, cancelled).run(List.of(source.toFile()), true);
  }

  private List<Path> storedFiles() throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(Files::isRegularFile)
          .filter(f -> !root.relativize(f).toString().startsWith("."))
          .toList();
    }
  }

  private long inFolder(String folder) throws IOException {
    Path dir = root.resolve(folder);
    if (!Files.isDirectory(dir)) {
      return 0;
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.count();
    }
  }

  private Map<String, byte[]> twoPatients() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("a/DICOM/S1/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    entries.put("a/DICOM/S1/IM2", bytes(image(STUDY_A, "1.2.3.2", "001", (byte) 2)));
    entries.put("b/deep/er/IM3.dcm", bytes(image(STUDY_B, "1.2.3.3", "002", (byte) 3)));
    return entries;
  }

  @Test
  void everyObjectOfSeveralPatientsAndNestedFoldersIsKeptByteForByte() throws IOException {
    Map<String, byte[]> entries = twoPatients();

    LibraryImport.Report report = importing(zip("study.zip", entries));

    assertEquals(3, report.stored());
    assertEquals(0, report.failed() + report.conflicts() + report.archivesRefused());
    assertEquals(2, catalog.studies().size());
    List<Path> stored = storedFiles();
    assertEquals(3, stored.size());
    for (byte[] original : entries.values()) {
      boolean found = false;
      for (Path file : stored) {
        found |= java.util.Arrays.equals(original, Files.readAllBytes(file));
      }
      assertTrue(found, "a stored object differs from its archive entry");
    }
  }

  @Test
  void theStudyOutlivesTheArchiveAndARestart() throws IOException {
    Path archive = zip("study.zip", twoPatients());
    importing(archive);
    catalog.save();

    Files.delete(archive);
    StudyStore again = open();
    catalog.reconcile(root, again);

    assertEquals(2, catalog.studies().size());
    assertEquals(3, storedFiles().size());
    for (Path file : storedFiles()) {
      assertTrue(StudyStore.isComplete(file));
    }
  }

  @Test
  void theOriginalArchiveIsOnlyRead() throws IOException {
    Path archive = zip("study.zip", twoPatients());
    byte[] before = Files.readAllBytes(archive);
    var modified = Files.getLastModifiedTime(archive);

    importing(archive);

    assertArrayEquals(before, Files.readAllBytes(archive));
    assertEquals(modified, Files.getLastModifiedTime(archive));
  }

  @Test
  void aMultiframeObjectIsKeptWhole() throws IOException {
    importing(zip("cine.zip", Map.of("CINE/IM1", bytes(multiframe("1.2.3.9")))));

    assertEquals(1, storedFiles().size());
    assertTrue(StudyStore.isComplete(storedFiles().getFirst()));
  }

  @Test
  void importingTheSameArchiveAgainAddsNothing() throws IOException {
    Path archive = zip("study.zip", twoPatients());
    importing(archive);

    LibraryImport.Report again = importing(archive);

    assertEquals(0, again.stored());
    assertEquals(3, again.alreadyHeld());
    assertEquals(3, storedFiles().size());
  }

  @Test
  void anIdenticalObjectTwiceInOneArchiveIsKeptOnce() throws IOException {
    byte[] same = bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1));
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("one/IM1", same);
    entries.put("two/IM1", same);

    LibraryImport.Report report = importing(zip("dup.zip", entries));

    assertEquals(1, report.stored());
    assertEquals(1, report.alreadyHeld());
  }

  @Test
  void theSameIdentifierWithOtherContentIsSetAsideAndReported() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("one/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    entries.put("two/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 9)));

    LibraryImport.Report report = importing(zip("clash.zip", entries));

    assertEquals(1, report.stored());
    assertEquals(1, report.conflicts());
    assertEquals(1, inFolder(".quarantine"));
  }

  private Path storedZip(String entryName, byte[] content) throws IOException {
    Path file = tmp.resolve("stored.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      ZipEntry entry = new ZipEntry(entryName);
      entry.setMethod(ZipEntry.STORED);
      entry.setSize(content.length);
      entry.setCompressedSize(content.length);
      CRC32 crc = new CRC32();
      crc.update(content);
      entry.setCrc(crc.getValue());
      out.putNextEntry(entry);
      out.write(content);
      out.closeEntry();
    }
    return file;
  }

  @Test
  void anEntryDamagedInsideTheArchiveIsReportedNotStored() throws IOException {
    byte[] content = bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 77));
    Path archive = storedZip("IM1", content);
    byte[] raw = Files.readAllBytes(archive);
    int at = indexOf(raw, new byte[] {77, 2, 3, 4});
    raw[at] = 78;
    Files.write(archive, raw);

    LibraryImport.Report report = importing(archive);

    assertEquals(0, report.stored());
    assertEquals(1, report.failed());
    assertTrue(report.firstProblem().contains("IM1"));
    assertEquals(0, storedFiles().size());
  }

  private static int indexOf(byte[] data, byte[] part) {
    outer:
    for (int i = 0; i <= data.length - part.length; i++) {
      for (int j = 0; j < part.length; j++) {
        if (data[i + j] != part[j]) {
          continue outer;
        }
      }
      return i;
    }
    throw new AssertionError("not found");
  }

  @Test
  void aDicomObjectNamedLikeAViewerFileIsStillKept() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("Viewer/image.ini", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    entries.put("__MACOSX/IM2", bytes(image(STUDY_A, "1.2.3.2", "001", (byte) 2)));
    entries.put("Viewer/viewer.exe", "program".getBytes(StandardCharsets.UTF_8));

    LibraryImport.Report report = importing(zip("named.zip", entries));

    assertEquals(2, report.stored());
    assertEquals(0, report.failed());
  }

  @Test
  void aDataSetWithNoFileHeaderIsRecognisedByItsFirstGroup() {
    assertTrue(LibraryImport.looksLikeDicom(new byte[] {8, 0, 5, 0}));
    assertFalse(LibraryImport.looksLikeDicom("MZ program".getBytes(StandardCharsets.UTF_8)));
    assertFalse(LibraryImport.looksLikeDicom(new byte[0]));
  }

  @Test
  void twoObjectsWithTheSameEntryNameAreBothKept() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("AAAA", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    entries.put("BBBB", bytes(image(STUDY_A, "1.2.3.2", "001", (byte) 2)));
    Path archive = zip("same.zip", entries);
    byte[] raw = Files.readAllBytes(archive);
    String renamed = new String(raw, StandardCharsets.ISO_8859_1).replace("BBBB", "AAAA");
    Files.write(archive, renamed.getBytes(StandardCharsets.ISO_8859_1));

    LibraryImport.Report report = importing(archive);

    assertEquals(2, report.stored());
    assertEquals(0, report.failed());
    assertEquals(2, storedFiles().size());
  }

  @Test
  void aDamagedArchiveInsideAFolderIsReported() throws IOException {
    Path folder = Files.createDirectories(tmp.resolve("folder"));
    Files.writeString(folder.resolve("damaged.zip"), "not really an archive");

    LibraryImport.Report report = importing(folder);

    assertEquals(1, report.archivesRefused());
    assertTrue(report.firstProblem().contains("damaged.zip"));
  }

  @Test
  void aPasswordProtectedArchiveIsJudgedByTheSameLimits() throws IOException {
    Path first = tmp.resolve("IM1");
    Path second = tmp.resolve("IM2");
    Files.write(first, new byte[] {1});
    Files.write(second, new byte[] {2});
    Path archive = tmp.resolve("locked-two.zip");
    ZipParameters parameters = new ZipParameters();
    parameters.setEncryptFiles(true);
    parameters.setEncryptionMethod(EncryptionMethod.AES);
    try (net.lingala.zip4j.ZipFile zip =
        new net.lingala.zip4j.ZipFile(archive.toFile(), "secret".toCharArray())) {
      zip.addFiles(List.of(first.toFile(), second.toFile()), parameters);
    }
    long max = Long.MAX_VALUE;

    assertTrue(
        ArchiveGuard.isRefused(archive.toFile(), new ArchiveGuard.Limits(1, max, max, max, 1)));
    assertFalse(ArchiveGuard.isRefused(archive.toFile()));
  }

  @Test
  void aDicomFileThatCannotBeReadIsReportedNotCountedAsOther() throws IOException {
    byte[] broken = new byte[200];
    System.arraycopy("DICM".getBytes(StandardCharsets.US_ASCII), 0, broken, 128, 4);
    java.util.Arrays.fill(broken, 132, 200, (byte) 0xFF);

    LibraryImport.Report report = importing(zip("broken.zip", Map.of("IM1", broken)));

    assertEquals(1, report.failed());
    assertEquals(0, report.notDicom());
  }

  @Test
  void aDamagedDcmWithNoMarkIsReportedNotCountedAsOther() throws IOException {
    LibraryImport.Report report =
        importing(zip("cut.zip", Map.of("IM1.dcm", "cut off".getBytes(StandardCharsets.UTF_8))));

    assertEquals(1, report.failed());
    assertEquals(0, report.notDicom());
  }

  @Test
  void aDicomdirInAnArchiveIsNotADamagedImage() throws IOException {
    Attributes directory = new Attributes();
    directory.setString(Tag.FileSetID, VR.CS, "DEMO");
    directory.newSequence(Tag.DirectoryRecordSequence, 0);
    Attributes meta =
        Attributes.createFileMetaInformation(
            "1.2.3.9", UID.MediaStorageDirectoryStorage, UID.ExplicitVRLittleEndian);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DicomOutputStream dicom = new DicomOutputStream(out, UID.ExplicitVRLittleEndian)) {
      dicom.writeDataset(meta, directory);
    }
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("DICOMDIR", out.toByteArray());
    entries.put("IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));

    LibraryImport.Report report = importing(zip("cd.zip", entries));

    assertEquals(0, report.failed());
    assertEquals(1, report.stored());
    assertEquals(1, report.notDicom());
  }

  private Path file(String name, byte[] content) throws IOException {
    Path path = tmp.resolve(name);
    Files.createDirectories(path.getParent());
    Files.write(path, content);
    return path;
  }

  private LibraryImport.Report referenced(Path... files) throws IOException {
    return new LibraryImport(store, () -> false)
        .expectingOnlyDicom(true)
        .run(Stream.of(files).map(Path::toFile).toList(), false);
  }

  @Test
  void imagesADicomdirNamesAreKeptAndOutliveTheDisc() throws IOException {
    Path one = file("disc/A/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    Path two = file("disc/A/IM2", bytes(image(STUDY_A, "1.2.3.2", "001", (byte) 2)));

    LibraryImport.Report report = referenced(one, two);
    Files.delete(one);
    Files.delete(two);

    assertEquals(2, report.stored());
    assertEquals(2, storedFiles().size());
    for (Path stored : storedFiles()) {
      assertTrue(StudyStore.isComplete(stored));
    }
  }

  @Test
  void anotherFileBesideThoseADicomdirNamesIsNotKept() throws IOException {
    Path named = file("disc/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    file("disc/OTHER", bytes(image(STUDY_B, "1.2.3.9", "002", (byte) 9)));

    referenced(named);

    assertEquals(1, storedFiles().size());
  }

  @Test
  void aNamedImageThatIsMissingOrUnreadableIsReported() throws IOException {
    Path good = file("disc/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    Path missing = tmp.resolve("disc/GONE");
    Path broken = file("disc/IM3", "not an image".getBytes(StandardCharsets.UTF_8));

    LibraryImport.Report report = referenced(good, missing, broken);

    assertEquals(1, report.stored());
    assertEquals(2, report.failed());
    assertEquals(0, report.notDicom());
  }

  @Test
  void openingTheSameDiscAgainAddsNothing() throws IOException {
    Path one = file("disc/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    referenced(one);

    LibraryImport.Report again = referenced(one);

    assertEquals(0, again.stored());
    assertEquals(1, again.alreadyHeld());
  }

  @Test
  void aFileThatOnlyLooksLikeAnArchiveIsReported() throws IOException {
    Path fake = tmp.resolve("bad.zip");
    Files.write(fake, new byte[] {'P', 'K', 3, 4, 9, 9, 9, 9, 9, 9});

    LibraryImport.Report report = importing(fake);

    assertEquals(1, report.archivesRefused());
    assertTrue(report.firstProblem().contains("bad.zip"));
  }

  @Test
  void aNamedZipThatIsNotOneIsReported() throws IOException {
    Path fake = tmp.resolve("text.zip");
    Files.writeString(fake, "plain text", StandardCharsets.UTF_8);

    assertEquals(1, importing(fake).archivesRefused());
  }

  @Test
  void aPasswordProtectedArchiveIsReportedNotOpened() throws IOException {
    Path content = tmp.resolve("IM1");
    Files.write(content, bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    Path archive = tmp.resolve("locked.zip");
    ZipParameters parameters = new ZipParameters();
    parameters.setEncryptFiles(true);
    parameters.setEncryptionMethod(EncryptionMethod.AES);
    try (net.lingala.zip4j.ZipFile zip =
        new net.lingala.zip4j.ZipFile(archive.toFile(), "secret".toCharArray())) {
      zip.addFile(content.toFile(), parameters);
    }

    LibraryImport.Report report = importing(archive);

    assertEquals(1, report.archivesRefused());
    assertEquals(0, report.stored());
    assertEquals(0, storedFiles().size());
  }

  @Test
  void anEntryNamedToEscapeCannotWriteOutsideTheStore() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("../../escaped.dcm", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));

    LibraryImport.Report report = importing(zip("evil.zip", entries));

    assertEquals(1, report.stored());
    assertFalse(Files.exists(tmp.resolve("escaped.dcm")));
    assertFalse(Files.exists(tmp.getParent().resolve("escaped.dcm")));
    assertEquals(1, storedFiles().size());
  }

  private ArchiveGuard.Verdict verdict(Path archive, long size, ArchiveGuard.Limits limits)
      throws IOException {
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      return ArchiveGuard.inspect(zip, size, limits);
    }
  }

  @Test
  void theGuardRefusesWhatWouldExpandTooFar() throws IOException {
    Path archive = zip("big.zip", Map.of("a", new byte[100_000], "b", new byte[100_000]));
    long size = Files.size(archive);
    long max = Long.MAX_VALUE;

    assertEquals(ArchiveGuard.Verdict.SAFE, verdict(archive, size, ArchiveGuard.DEFAULT));
    assertEquals(
        ArchiveGuard.Verdict.TOO_MANY_FILES,
        verdict(archive, size, new ArchiveGuard.Limits(1, max, max, max, 1)));
    assertEquals(
        ArchiveGuard.Verdict.FILE_TOO_LARGE,
        verdict(archive, size, new ArchiveGuard.Limits(9, 10, max, max, 1)));
    assertEquals(
        ArchiveGuard.Verdict.TOO_LARGE,
        verdict(archive, size, new ArchiveGuard.Limits(9, max, 10, max, 1)));
    assertEquals(
        ArchiveGuard.Verdict.SUSPICIOUS_RATIO,
        verdict(archive, size, new ArchiveGuard.Limits(9, max, max, 10, 5)));
    assertEquals(ArchiveGuard.Verdict.OVERLAPPING, verdict(archive, 1, ArchiveGuard.DEFAULT));
  }

  @Test
  void anArchiveTheGuardRefusesIsReportedAndNothingIsStored() throws IOException {
    Path archive = zip("study.zip", twoPatients());
    long max = Long.MAX_VALUE;

    LibraryImport.Report report =
        new LibraryImport(store, () -> false, new ArchiveGuard.Limits(1, max, max, max, 1), _ -> {})
            .run(List.of(archive.toFile()), true);

    assertEquals(1, report.archivesRefused());
    assertEquals(0, storedFiles().size());
  }

  @Test
  void stoppingKeepsWhatWasDoneAndSaysSoAndTheRestCanFollow() throws IOException {
    Path archive = zip("study.zip", twoPatients());
    AtomicInteger asked = new AtomicInteger();

    // The walk asks once; then once before each entry: stop before the second entry.
    LibraryImport.Report report = importing(archive, store, () -> asked.incrementAndGet() > 2);

    assertTrue(report.cancelled());
    assertEquals(1, report.stored());
    assertEquals(0, inFolder(".incoming"));
    catalog.save();

    StudyStore again = open();
    catalog.reconcile(root, again);
    LibraryImport.Report rest = importing(archive, again, () -> false);

    assertEquals(2, rest.stored());
    assertEquals(1, rest.alreadyHeld());
    assertEquals(2, catalog.studies().size());
  }

  private StudyStore withFreeBytes(long free) {
    return new StudyStore(
        root,
        true,
        Clock.systemUTC(),
        _ -> free,
        file -> Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW),
        Integer.MAX_VALUE);
  }

  @Test
  void aNearlyFullDiskStopsTheImportHonestly() throws IOException {
    LibraryImport.Report report =
        importing(
            zip("study.zip", twoPatients()),
            withFreeBytes(StudyStore.MIN_FREE_BYTES - 1),
            () -> false);

    assertTrue(report.outOfSpace());
    assertEquals(0, report.stored());
    assertEquals(0, storedFiles().size());
  }

  @Test
  void anEntryThatWouldNotFitIsNotStarted() throws IOException {
    LibraryImport.Report report =
        importing(
            zip("study.zip", twoPatients()),
            withFreeBytes(StudyStore.MIN_FREE_BYTES + 10),
            () -> false);

    assertTrue(report.outOfSpace());
    assertEquals(0, inFolder(".incoming"));
  }

  @Test
  void aBundledViewerIsSkippedAndOtherFilesAreCounted() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("DICOM/IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1)));
    entries.put("Viewer/viewer.exe", "program".getBytes(StandardCharsets.UTF_8));
    entries.put("README.TXT", "hello".getBytes(StandardCharsets.UTF_8));

    LibraryImport.Report report = importing(zip("cd.zip", entries));

    assertEquals(1, report.stored());
    assertEquals(1, report.notDicom());
    assertEquals(0, report.archivesRefused());
  }

  @Test
  void anArchiveInsideAnArchiveIsReportedNotOpened() throws IOException {
    Path inner = zip("inner.zip", Map.of("IM1", bytes(image(STUDY_A, "1.2.3.1", "001", (byte) 1))));
    LibraryImport.Report report =
        importing(zip("outer.zip", Map.of("inner.zip", Files.readAllBytes(inner))));

    assertEquals(1, report.nestedArchives());
    assertEquals(0, report.stored());
  }

  @Test
  void anArchiveChosenByHandWithNoDicomIsReported() throws IOException {
    assertEquals(
        1,
        importing(zip("notes.zip", Map.of("a.txt", "x".getBytes(StandardCharsets.UTF_8))))
            .archivesRefused());
  }

  @Test
  void anArchiveInAFolderIsImportedToo() throws IOException {
    Path folder = Files.createDirectories(tmp.resolve("folder"));
    Path archive = zip("study.zip", twoPatients());
    Files.move(archive, folder.resolve("study.zip"));

    assertEquals(3, importing(folder).stored());
  }

  @Test
  void theStoreIsNotCopiedOntoItselfEvenThroughALink() throws IOException {
    importing(zip("study.zip", twoPatients()));
    Path link = Files.createSymbolicLink(tmp.resolve("link"), root);

    LibraryImport.Report report = importing(link);

    assertEquals(0, report.stored() + report.alreadyHeld());
    assertEquals(3, storedFiles().size());
  }

  @Test
  void anUnreadableSourceIsNotASilentNothing() throws IOException {
    LibraryImport.Report report = importing(tmp.resolve("gone"));

    assertEquals(1, report.failed());
  }
}
