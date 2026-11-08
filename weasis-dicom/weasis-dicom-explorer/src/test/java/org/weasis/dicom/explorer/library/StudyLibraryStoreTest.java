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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.index.StudyEntry;
import org.weasis.dicom.explorer.library.StudyStore.Outcome;

/** The store, the catalog and import, on synthetic images. */
class StudyLibraryStoreTest {
  private static final String STUDY = "1.2.826.0.1.3680043.8.498.10";
  private static final String SERIES = "1.2.826.0.1.3680043.8.498.11";

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

  private static Attributes image(String sop, String id, byte pixel) {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, UID.ComputedRadiographyImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, sop);
    a.setString(Tag.StudyInstanceUID, VR.UI, STUDY);
    a.setString(Tag.SeriesInstanceUID, VR.UI, SERIES);
    a.setString(Tag.PatientName, VR.PN, "Synthetic^Test");
    a.setString(Tag.PatientID, VR.LO, id);
    a.setString(Tag.Modality, VR.CS, "CR");
    a.setString(Tag.AcquisitionDate, VR.DA, "20260115");
    a.setString(Tag.StudyDate, VR.DA, "20260110");
    a.setBytes(Tag.PixelData, VR.OB, new byte[] {pixel, 2, 3, 4});
    return a;
  }

  private Path write(Path file, Attributes a, String implementationUid) throws IOException {
    Files.createDirectories(file.getParent());
    Attributes fmi = a.createFileMetaInformation(UID.ExplicitVRLittleEndian);
    fmi.setString(Tag.ImplementationClassUID, VR.UI, implementationUid);
    try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
      out.writeDataset(fmi, a);
    }
    return file;
  }

  private Outcome commit(Attributes a, String implementationUid) throws IOException {
    Path temp = write(store.newTempFile(), a, implementationUid);
    return store.commit(temp, a, STUDY, SERIES, a.getString(Tag.SOPInstanceUID)).outcome();
  }

  private long quarantined() throws IOException {
    Path folder = root.resolve(".quarantine");
    if (!Files.isDirectory(folder)) {
      return 0;
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.count();
    }
  }

  private Path studyFolder() throws IOException {
    try (Stream<Path> dirs = Files.list(root)) {
      return dirs.filter(d -> !d.getFileName().toString().startsWith("."))
          .findFirst()
          .orElseThrow();
    }
  }

  @Test
  void aStoredImageIsCatalogued() throws IOException {
    assertEquals(Outcome.STORED, commit(image("1.1", "00123", (byte) 1), "1.2.3"));

    CatalogStudy row = catalog.studies().getFirst();
    assertEquals("00123", row.patientId());
    assertEquals(1, row.imageCount());
    assertEquals("2026-01-15", row.imageDate());
    assertTrue(row.firstStored() > 0);
    assertTrue(row.folder().contains("MRN") || row.folder().contains("00123"), row.folder());
  }

  @Test
  void theFolderIsNamedAfterTheImageDateNotTheDayItArrived() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    assertTrue(studyFolder().getFileName().toString().contains("2026-01-15"));
  }

  @Test
  void aResendWithTheSameContentIsDroppedEvenFromAnotherSender() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");

    assertEquals(Outcome.ALREADY_HELD, commit(image("1.1", "00123", (byte) 1), "9.9.9"));

    assertEquals(1, catalog.studies().getFirst().imageCount());
    assertEquals(0, quarantined());
  }

  @Test
  void theSameIdentifierWithOtherContentIsSetAsideNotDiscarded() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");

    assertEquals(Outcome.CONFLICT, commit(image("1.1", "00123", (byte) 9), "1.2.3"));

    assertEquals(1, quarantined());
    assertEquals(1, catalog.studies().getFirst().imageCount());
  }

  @Test
  void anotherPatientInTheSameStudyIsSetAside() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");

    assertEquals(Outcome.CONFLICT, commit(image("1.2", "00999", (byte) 1), "1.2.3"));

    assertEquals(1, quarantined());
    assertEquals(1, catalog.studies().getFirst().imageCount());
  }

  @Test
  void aResendOfAClashDoesNotPileUpCopies() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    commit(image("1.1", "00123", (byte) 9), "1.2.3");
    commit(image("1.1", "00123", (byte) 9), "1.2.3");
    assertEquals(1, quarantined());
  }

  @Test
  void theCatalogSurvivesARestart() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    catalog.save();

    open();

    assertEquals(1, catalog.studies().size());
    assertEquals("00123", catalog.studies().getFirst().patientId());
    assertEquals(STUDY, catalog.studies().getFirst().studyUid());
  }

  @Test
  void anInterruptionBeforeTheCatalogWasSavedIsRepairedFromTheFiles() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    commit(image("1.2", "00123", (byte) 2), "1.2.3");
    // no save: the crash came after the files were durable

    open();
    assertTrue(catalog.studies().isEmpty());
    StudyCatalog.Reconciled result = catalog.reconcile(root);

    assertEquals(1, result.rebuilt());
    assertEquals(2, catalog.studies().getFirst().imageCount());
  }

  @Test
  void aCountThatDiffersIsReadAgain() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    catalog.save();
    commit(image("1.2", "00123", (byte) 2), "1.2.3");

    open();
    assertEquals(1, catalog.studies().getFirst().imageCount());
    catalog.reconcile(root);

    assertEquals(2, catalog.studies().getFirst().imageCount());
  }

  @Test
  void aDamagedCatalogIsRebuilt() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    catalog.save();
    Files.writeString(root.resolve(".catalog/studies.tsv"), "not a catalog");

    open();
    catalog.reconcile(root);

    assertEquals(1, catalog.studies().size());
  }

  @Test
  void aStudyFolderThatIsGoneLeavesTheCatalog() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");
    catalog.save();
    store.deleteStudy(studyFolder());

    open();
    StudyCatalog.Reconciled result = catalog.reconcile(root);

    assertEquals(1, result.removed());
    assertTrue(catalog.studies().isEmpty());
  }

  @Test
  void deletingAStudyTakesItOutOfTheCatalogAtOnce() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");

    store.deleteStudy(studyFolder());

    assertTrue(catalog.studies().isEmpty());
  }

  @Test
  void leftoversOfACrashGoOnceButNotWhileTheStoreIsInUse() throws IOException {
    Path live = Files.createFile(store.newTempFile());

    store.prepare();
    assertTrue(Files.exists(live), "a second prepare must not delete a file being written");

    open();
    assertFalse(Files.exists(live), "a restart removes it");
  }

  private LibraryImport.Report importFrom(Path folder, boolean recursive) throws IOException {
    return new LibraryImport(store, () -> false).run(List.of(folder.toFile()), recursive);
  }

  @Test
  void anImportedStudyOutlastsItsSourceAndTheOriginalIsUntouched() throws IOException {
    Path disc = tmp.resolve("disc");
    Path original = write(disc.resolve("IMG1"), image("1.1", "00123", (byte) 1), "1.2.3");
    byte[] before = Files.readAllBytes(original);

    LibraryImport.Report report = importFrom(disc, true);

    assertEquals(1, report.stored());
    assertEquals(0, report.failed());
    org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(original));
    Files.delete(original);
    Files.delete(disc);
    try (Stream<Path> files = Files.list(studyFolder())) {
      assertEquals(1, files.count());
    }
    assertEquals(1, catalog.studies().size());
  }

  @Test
  void importingTwiceAddsNothing() throws IOException {
    Path disc = tmp.resolve("disc");
    write(disc.resolve("IMG1"), image("1.1", "00123", (byte) 1), "1.2.3");
    importFrom(disc, true);

    LibraryImport.Report again = importFrom(disc, true);

    assertEquals(0, again.stored());
    assertEquals(1, again.alreadyHeld());
    assertEquals(1, catalog.studies().getFirst().imageCount());
  }

  @Test
  void aFileThatIsNotDicomIsLeftOut() throws IOException {
    Path folder = Files.createDirectories(tmp.resolve("mixed"));
    Files.writeString(folder.resolve("notes.txt"), "not an image");

    LibraryImport.Report report = importFrom(folder, true);

    assertEquals(0, report.stored());
    assertEquals(1, report.notDicom());
    assertEquals(0, report.failed());
  }

  @Test
  void subfoldersAreOnlyWalkedWhenAskedFor() throws IOException {
    Path folder = tmp.resolve("top");
    write(folder.resolve("deep/IMG1"), image("1.1", "00123", (byte) 1), "1.2.3");

    assertEquals(0, importFrom(folder, false).stored());
    assertEquals(1, importFrom(folder, true).stored());
  }

  @Test
  void theStoreItselfIsNotImported() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");

    LibraryImport.Report report = importFrom(root, true);

    assertEquals(0, report.stored() + report.alreadyHeld() + report.notDicom());
  }

  @Test
  void aNearlyFullDiskStopsTheImportVisibly() throws IOException {
    StudyStore full =
        new StudyStore(
            root,
            true,
            java.time.Clock.systemUTC(),
            _ -> StudyStore.MIN_FREE_BYTES - 1,
            f -> Files.newOutputStream(f),
            Integer.MAX_VALUE);
    Path disc = tmp.resolve("disc");
    write(disc.resolve("IMG1"), image("1.1", "00123", (byte) 1), "1.2.3");

    LibraryImport.Report report =
        new LibraryImport(full, () -> false).run(List.of(disc.toFile()), true);

    assertTrue(report.outOfSpace());
    assertEquals(0, report.stored());
  }

  @Test
  void aStudyInBothPlacesIsOneRowThatOpensFromBoth() {
    List<StudyEntry> merged =
        StudyEntry.merge(List.of(entry("A", 1, "/library/a")), List.of(entry("A", 5, "/disc/a")));

    assertEquals(1, merged.size());
    assertEquals(List.of("/library/a", "/disc/a"), merged.getFirst().containers());
    assertEquals(5, merged.getFirst().imageCount());
    assertEquals(
        2,
        StudyEntry.merge(List.of(entry("A", 1, "/library/a")), List.of(entry("B", 1, "/b")))
            .size());
  }

  private Path derived(Path file) throws IOException {
    return write(file, image("7.7", "00123", (byte) 5), "1.2.3");
  }

  @Test
  void aDerivedObjectIsDurableAndCataloguedWhenSaveReturns() throws IOException {
    StoredInstance saved = store.saveDerived(this::derived);

    assertTrue(Files.isRegularFile(saved.file()));
    assertEquals(root, saved.file().getParent().getParent());
    assertEquals(1, catalog.studies().getFirst().imageCount());
    catalog.save();

    open();
    assertEquals(1, catalog.studies().getFirst().imageCount());
    assertEquals("7.7", StudyStore.readHeader(saved.file()).getString(Tag.SOPInstanceUID));
  }

  @Test
  void aWriterThatFailsLeavesNothingAndIsNotSaved() throws IOException {
    org.junit.jupiter.api.Assertions.assertThrows(
        IOException.class,
        () ->
            store.saveDerived(
                f -> {
                  Files.write(f, new byte[16]);
                  throw new IOException("disk failure");
                }));

    assertTrue(catalog.studies().isEmpty());
    try (Stream<Path> left = Files.list(root.resolve(".incoming"))) {
      assertEquals(0, left.count());
    }
  }

  @Test
  void aFileThatIsNotDicomIsNotSaved() throws IOException {
    org.junit.jupiter.api.Assertions.assertThrows(
        IOException.class, () -> store.saveDerived(f -> Files.writeString(f, "not DICOM")));

    assertTrue(catalog.studies().isEmpty());
  }

  @Test
  void aDerivedObjectForAnotherPatientIsRefusedAndKept() throws IOException {
    commit(image("1.1", "00123", (byte) 1), "1.2.3");

    org.junit.jupiter.api.Assertions.assertThrows(
        IOException.class,
        () -> store.saveDerived(f -> write(f, image("7.7", "00999", (byte) 5), "1.2.3")));

    assertEquals(1, quarantined());
    assertEquals(1, catalog.studies().getFirst().imageCount());
  }

  private static StudyEntry entry(String uid, int images, String place) {
    return new StudyEntry(
        uid,
        "n",
        "i",
        null,
        null,
        null,
        null,
        null,
        "CR",
        1,
        images,
        List.of(place),
        List.of(place),
        false);
  }

  @Test
  void aHeaderIsReadWithoutPixelsAndAnythingElseIsNull() throws IOException {
    Path image = write(tmp.resolve("x.dcm"), image("1.1", "00123", (byte) 1), "1.2.3");
    Attributes header = StudyStore.readHeader(image);
    assertNotNull(header);
    assertEquals("00123", header.getString(Tag.PatientID));
    assertFalse(header.contains(Tag.PixelData));

    File text = tmp.resolve("t.txt").toFile();
    Files.writeString(text.toPath(), "hello");
    assertNull(StudyStore.readHeader(text.toPath()));
  }
}
