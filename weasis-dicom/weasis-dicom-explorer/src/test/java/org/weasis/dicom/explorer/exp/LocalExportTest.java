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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipFile;
import javax.swing.JComponent;
import org.dcm4che3.data.Attributes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.codec.DicomElement;
import org.weasis.dicom.explorer.DicomModel;

class LocalExportTest {

  @TempDir Path tmp;

  @Test
  void namesTheFolderAfterThePatient() {
    assertEquals(
        new File(tmp.toFile(), "DOE JOHN 12345"),
        LocalExport.patientFolder(tmp.toFile(), "DOE JOHN 12345"));
  }

  @Test
  void numbersTheFolderWhenThePatientWasExportedThereBefore() throws IOException {
    Files.createDirectory(tmp.resolve("DOE JOHN 12345"));
    Files.createDirectory(tmp.resolve("DOE JOHN 12345 (2)"));
    assertEquals(
        new File(tmp.toFile(), "DOE JOHN 12345 (3)"),
        LocalExport.patientFolder(tmp.toFile(), "DOE JOHN 12345"));
  }

  @Test
  void dropsCharactersAFolderNameCannotHold() {
    File dir = LocalExport.patientFolder(tmp.toFile(), "DOE/JOHN: 12345");
    assertEquals(tmp.toFile(), dir.getParentFile());
    assertEquals(-1, dir.getName().indexOf('/'));
    assertEquals(-1, dir.getName().indexOf(':'));
  }

  @Test
  void putsTheFirstNameFirst() {
    assertEquals("Abebe Kebede", LocalExport.fullName("Kebede^Abebe"));
    assertEquals("Abebe Kebede Tesfaye", LocalExport.fullName("Tesfaye^Abebe^Kebede"));
    assertEquals("Dr Abebe Kebede Jr", LocalExport.fullName("Kebede^Abebe^^Dr^Jr"));
    assertEquals("Abebe Kebede", LocalExport.fullName("Kebede^Abebe=\u30a2\u30d9\u30d9"));
  }

  @Test
  void keepsANameTypedAsOneText() {
    assertEquals("ABEBE KEBEDE TESFAYE", LocalExport.fullName("ABEBE  KEBEDE TESFAYE "));
    assertEquals("", LocalExport.fullName(null));
    assertEquals("", LocalExport.fullName("^"));
  }

  /**
   * Export DICOM with "Include DICOMDIR" (the default) into a patient folder: the folder does not
   * exist yet, and the DICOMDIR is the first file written into it. Until 7 Nov 2026 the export
   * ended there with "No such file or directory" and nothing was saved.
   */
  @Test
  void createsThePatientFolderBeforeWritingTheDicomdir() throws IOException {
    DicomModel model = new DicomModel();
    CheckTreeModel tree = new CheckTreeModel(model);
    LocalExport export = new HeadlessExport(model, tree);
    File patientDir = new File(tmp.toFile(), "DOE JOHN 12345");
    Properties pref = new Properties();
    pref.setProperty(LocalExport.INC_DICOMDIR, "true");

    export.writeDicom(new NoTask(), patientDir, tree, pref, new ExportOutcome());

    assertTrue(new File(patientDir, "DICOMDIR").isFile());
  }

  @Test
  void writesWithoutDicomdirIntoAFolderThatDoesNotExistYet() throws IOException {
    DicomModel model = new DicomModel();
    CheckTreeModel tree = new CheckTreeModel(model);
    LocalExport export = new HeadlessExport(model, tree);
    File patientDir = new File(tmp.toFile(), "DOE JOHN 12345");
    Properties pref = new Properties();
    pref.setProperty(LocalExport.INC_DICOMDIR, "false");

    export.writeDicom(new NoTask(), patientDir, tree, pref, new ExportOutcome());

    assertTrue(patientDir.isDirectory());
    assertFalse(new File(patientDir, "DICOMDIR").exists());
  }

  @Test
  void aFailedExportIsReported() {
    FutureTask<Boolean> failed =
        new FutureTask<>(
            () -> {
              throw new IOException("No such file or directory");
            });
    failed.run();
    Optional<Throwable> failure = LocalExport.failure(failed);
    assertTrue(failure.isPresent());
    assertEquals("No such file or directory", failure.get().getMessage());
  }

  @Test
  void aFinishedOrCancelledExportIsNotReported() {
    FutureTask<Boolean> done = new FutureTask<>(() -> true);
    done.run();
    assertTrue(LocalExport.failure(done).isEmpty());

    FutureTask<Boolean> cancelled = new FutureTask<>(() -> true);
    cancelled.cancel(true);
    assertTrue(LocalExport.failure(cancelled).isEmpty());

    assertTrue(LocalExport.failure(new FutureTask<>(() -> true)).isEmpty());
  }

  @Test
  void nothingTickedMeansNothingToExport() {
    assertFalse(LocalExport.hasSomethingToExport(new CheckTreeModel(new DicomModel())));
  }

  @Test
  void aSavedDicomFileIsCounted() throws IOException {
    File destination = tmp.resolve("study/series/image").toFile();
    DicomElement dcm = mock(DicomElement.class);
    when(dcm.saveToFile(any(), any()))
        .thenAnswer(
            call -> {
              Files.write(call.<File>getArgument(0).toPath(), new byte[] {1});
              return new Attributes();
            });
    ExportOutcome outcome = new ExportOutcome();

    LocalExport.saveDicomFile(dcm, destination, null, null, null, "IMG", outcome);

    assertTrue(destination.isFile());
    assertEquals(1, outcome.written());
    assertEquals(ExportOutcome.Status.COMPLETED, outcome.status(false, null));
  }

  @Test
  void aDicomFileThatCouldNotBeWrittenIsCountedAndLeavesNothing() throws IOException {
    File destination = tmp.resolve("image").toFile();
    DicomElement dcm = mock(DicomElement.class);
    when(dcm.saveToFile(any(), any())).thenReturn(null);
    ExportOutcome outcome = new ExportOutcome();

    LocalExport.saveDicomFile(dcm, destination, null, null, null, "IMG", outcome);

    assertFalse(destination.exists());
    assertEquals(1, outcome.failed());
    assertEquals(ExportOutcome.Status.FAILED, outcome.status(false, null));
    try (var files = Files.list(tmp)) {
      assertEquals(0, files.count());
    }
  }

  @Test
  void aWriterThatClaimsSuccessWithoutAFileIsAFailure() {
    File destination = tmp.resolve("image").toFile();
    DicomElement dcm = mock(DicomElement.class);
    when(dcm.saveToFile(any(), any())).thenReturn(new Attributes());
    ExportOutcome outcome = new ExportOutcome();

    LocalExport.saveDicomFile(dcm, destination, null, null, null, "IMG", outcome);

    assertEquals(0, outcome.written());
    assertEquals(1, outcome.failed());
  }

  @Test
  void aZipIsPublishedOnlyWhenItCanBeReadBack() throws IOException {
    Path content = Files.createDirectory(tmp.resolve("content"));
    Files.write(content.resolve("DICOMDIR"), new byte[] {1, 2});
    Path zip = tmp.resolve("export.zip");

    LocalExport.publishZip(content, zip);

    try (ZipFile written = new ZipFile(zip.toFile())) {
      assertNotNull(written.getEntry("DICOMDIR"));
    }
  }

  @Test
  void anEmptyZipIsNotPublished() throws IOException {
    Path content = Files.createDirectory(tmp.resolve("content"));
    Path zip = tmp.resolve("export.zip");

    assertThrows(IOException.class, () -> LocalExport.publishZip(content, zip));

    assertFalse(Files.exists(zip));
  }

  @Test
  void aZipThatFailsCannotBeReportedAsSaved() {
    ExportOutcome outcome = new ExportOutcome();
    outcome.saved();
    outcome.saved();

    outcome.lostAll(new IOException("Disk full"));

    assertEquals(ExportOutcome.Status.FAILED, outcome.status(false, null));
    assertEquals(0, outcome.written());
    assertEquals(2, outcome.failed());
    assertEquals("Disk full", outcome.firstProblem());
  }

  @Test
  void theResultIsToldApart() {
    ExportOutcome none = new ExportOutcome();
    assertEquals(ExportOutcome.Status.FAILED, none.status(false, null));
    assertEquals(ExportOutcome.Status.CANCELLED, none.status(true, null));

    ExportOutcome some = new ExportOutcome();
    some.saved();
    assertEquals(ExportOutcome.Status.COMPLETED, some.status(false, null));
    assertEquals(ExportOutcome.Status.PARTIAL, some.status(false, new IOException("DICOMDIR")));
    assertEquals(ExportOutcome.Status.CANCELLED, some.status(true, null));

    some.failed("IMG", new IOException("Disk full"));
    assertEquals(ExportOutcome.Status.PARTIAL, some.status(false, null));
    assertEquals("IMG: Disk full", some.firstProblem());
  }

  @Test
  void theMessageGivesTheConfirmedCount() {
    ExportOutcome outcome = new ExportOutcome();
    outcome.saved();
    outcome.saved();
    outcome.failed("IMG", new IOException("Disk full"));
    File target = tmp.toFile();

    String partial = LocalExport.resultMessage(ExportOutcome.Status.PARTIAL, target, outcome, null);

    assertTrue(partial.contains("2 file(s)"), partial);
    assertTrue(partial.contains(target.getPath()), partial);
    assertTrue(partial.contains("IMG: Disk full"), partial);
    assertTrue(partial.contains("not complete"), partial);
  }

  /** The export page without the viewer's preference store, which needs the running app. */
  private static final class HeadlessExport extends LocalExport {
    HeadlessExport(DicomModel model, CheckTreeModel tree) {
      super(model, tree);
    }

    @Override
    protected List<JComponent> getAdditionalOption() {
      return List.of();
    }

    @Override
    protected Properties getPreferences() {
      return new Properties();
    }
  }

  private static final class NoTask extends ExplorerTask<Boolean, String> {
    NoTask() {
      super("test", false);
    }

    @Override
    protected Boolean doInBackground() {
      return true;
    }
  }
}
