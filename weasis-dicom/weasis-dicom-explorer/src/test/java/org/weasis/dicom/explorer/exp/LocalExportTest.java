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
import javax.swing.tree.DefaultMutableTreeNode;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.media.DicomDirWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.codec.DicomElement;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.imp.DicomDirLoader;

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

    export.writeDicom(new NoTask(), patientDir, tree, pref, new ExportOutcome(), null);

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

    export.writeDicom(new NoTask(), patientDir, tree, pref, new ExportOutcome(), null);

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
  void readableNamesLeaveNoDicomdir() throws IOException {
    DicomModel model = new DicomModel();
    CheckTreeModel tree = new CheckTreeModel(model);
    LocalExport export = new HeadlessExport(model, tree);
    File patientDir = new File(tmp.toFile(), "DOE JOHN");
    Properties pref = new Properties();
    pref.setProperty(LocalExport.INC_DICOMDIR, "true");

    export.writeDicom(
        new NoTask(), patientDir, tree, pref, new ExportOutcome(), new ExportNaming(true, true));

    assertTrue(patientDir.isDirectory());
    assertFalse(new File(patientDir, "DICOMDIR").exists());
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

  @Test
  void dicomdirFileIdsUseOnlyWhatTheStandardAllows() {
    for (String uid : List.of("1.2.3", "1.2.840.113619.2.55.3.604688119", "9.9.9.9.1")) {
      assertTrue(LocalExport.makeFileIDs(uid).matches("[0-9A-Z]{1,8}"), uid);
    }
  }

  /**
   * A real DICOMDIR and ZIP, for checking with a reader that is not this program. Set
   * ao.export.sample to keep them; the data is synthetic.
   */
  @Test
  void writesADicomdirAndZipAnotherReaderCanCheck() throws Exception {
    String keep = System.getProperty("ao.export.sample");
    Path root = keep == null ? tmp : Path.of(keep);
    File media = root.resolve("media").toFile();
    Files.createDirectories(media.toPath());
    Attributes data = syntheticImage();
    DicomElement dcm = mock(DicomElement.class);
    when(dcm.saveToFile(any(), any()))
        .thenAnswer(
            call -> {
              try (DicomOutputStream out = new DicomOutputStream(call.<File>getArgument(0))) {
                out.writeDataset(data.createFileMetaInformation(UID.ExplicitVRLittleEndian), data);
              }
              return data;
            });
    ExportOutcome outcome = new ExportOutcome();
    String sop = LocalExport.makeFileIDs(data.getString(Tag.SOPInstanceUID));
    File image =
        new File(
            media,
            String.join(
                File.separator,
                "DICOM",
                LocalExport.makeFileIDs("patient"),
                LocalExport.makeFileIDs(data.getString(Tag.StudyInstanceUID)),
                LocalExport.makeFileIDs(data.getString(Tag.SeriesInstanceUID)),
                sop));

    try (DicomDirWriter writer = DicomDirLoader.open(new File(media, "DICOMDIR"))) {
      LocalExport.saveDicomFile(
          dcm,
          image,
          new DicomElement.DicomExportParameters(null, true, List.of(), 0, 0),
          writer,
          new DefaultMutableTreeNode(),
          sop,
          outcome);
    }
    assertEquals(1, outcome.written(), outcome.firstProblem());

    Path zip = root.resolve("media.zip");
    Files.deleteIfExists(zip);
    LocalExport.publishZip(media.toPath(), zip);
    try (ZipFile written = new ZipFile(zip.toFile())) {
      assertNotNull(written.getEntry("DICOMDIR"));
    }
  }

  private static Attributes syntheticImage() {
    Attributes a = new Attributes();
    a.setString(Tag.SOPClassUID, VR.UI, UID.ComputedRadiographyImageStorage);
    a.setString(Tag.SOPInstanceUID, VR.UI, "1.2.826.0.1.3680043.8.498.1");
    a.setString(Tag.StudyInstanceUID, VR.UI, "1.2.826.0.1.3680043.8.498.2");
    a.setString(Tag.SeriesInstanceUID, VR.UI, "1.2.826.0.1.3680043.8.498.3");
    a.setString(Tag.PatientName, VR.PN, "Synthetic^Test");
    a.setString(Tag.PatientID, VR.LO, "00123");
    a.setString(Tag.Modality, VR.CS, "CR");
    a.setString(Tag.StudyDate, VR.DA, "20260110");
    a.setString(Tag.StudyTime, VR.TM, "101500");
    a.setString(Tag.StudyID, VR.SH, "1");
    a.setString(Tag.AccessionNumber, VR.SH, "");
    a.setInt(Tag.SeriesNumber, VR.IS, 1);
    a.setInt(Tag.InstanceNumber, VR.IS, 1);
    a.setInt(Tag.Rows, VR.US, 2);
    a.setInt(Tag.Columns, VR.US, 2);
    a.setInt(Tag.BitsAllocated, VR.US, 8);
    a.setInt(Tag.BitsStored, VR.US, 8);
    a.setInt(Tag.HighBit, VR.US, 7);
    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    a.setInt(Tag.PixelRepresentation, VR.US, 0);
    a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    a.setBytes(Tag.PixelData, VR.OB, new byte[] {1, 2, 3, 4});
    return a;
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
