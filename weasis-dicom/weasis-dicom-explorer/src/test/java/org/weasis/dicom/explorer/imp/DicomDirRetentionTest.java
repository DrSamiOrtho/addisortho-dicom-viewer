/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.library.StudyLibrary;
import org.weasis.dicom.explorer.wado.LoadSeries;

/** A CD opened through its DICOMDIR is kept in the library, on every route that loads one. */
class DicomDirRetentionTest {
  private final File dicomdir = new File(System.getProperty("java.io.tmpdir"), "DICOMDIR-test");

  @Test
  void aReadableDicomdirSendsItsImagesToTheLibrary() throws Exception {
    dicomdir.createNewFile();
    List<File> images = List.of(new File("IM1"), new File("IM2"));
    DicomModel model = mock(DicomModel.class);
    StudyLibrary library = mock(StudyLibrary.class);
    try (MockedStatic<StudyLibrary> statics = mockStatic(StudyLibrary.class);
        MockedConstruction<DicomDirLoader> loaders =
            mockConstruction(
                DicomDirLoader.class,
                (loader, _) -> {
                  when(loader.readDicomDir()).thenReturn(List.of(mock(LoadSeries.class)));
                  when(loader.referencedFiles()).thenReturn(images);
                })) {
      statics.when(StudyLibrary::getInstance).thenReturn(library);

      DicomDirImport.loadDicomDir(dicomdir, model, true);

      verify(library).retainReferenced(eq(images), eq(model), eq(0), any());
      assertEquals(1, loaders.constructed().size());
    } finally {
      dicomdir.delete();
    }
  }

  @Test
  void anEntryCannotClimbOutOfTheDicomdirFolder() {
    File disc = new File("disc");

    assertTrue(DicomDirLoader.isInside(disc, new File(disc, "DICOM/S1/IM1")));
    assertFalse(DicomDirLoader.isInside(disc, new File(disc, "../secret/IM1")));
    assertFalse(DicomDirLoader.isInside(disc, new File(disc, "DICOM/../../IM1")));
    assertFalse(DicomDirLoader.isInside(disc, new File("/elsewhere/IM1")));
    assertTrue(DicomDirLoader.isInside(new File("").getAbsoluteFile(), new File("IM1")));
  }

  @Test
  void aLinkInsideTheFolderThatLeadsOutIsRefused(@TempDir Path tmp) throws Exception {
    Path disc = Files.createDirectories(tmp.resolve("disc"));
    Path outside = Files.writeString(tmp.resolve("outside"), "x");
    Files.writeString(disc.resolve("IM1"), "y");
    Path link = Files.createSymbolicLink(disc.resolve("LINK"), outside);

    assertTrue(DicomDirLoader.isInside(disc.toFile(), disc.resolve("IM1").toFile()));
    assertFalse(DicomDirLoader.isInside(disc.toFile(), link.toFile()));
    assertTrue(DicomDirLoader.isInside(disc.toFile(), disc.resolve("MISSING").toFile()));
  }

  @Test
  void aDicomdirWhoseEveryImageIsMissingSaysHowManyForTheDiscCopy() throws Exception {
    dicomdir.createNewFile();
    try (MockedConstruction<DicomDirLoader> _ =
        mockConstruction(
            DicomDirLoader.class,
            (loader, _) -> {
              when(loader.readDicomDir()).thenReturn(List.of());
              when(loader.referencedFiles()).thenReturn(List.of(new File("IM1"), new File("IM2")));
            })) {

      DicomDirImport.Opened opened =
          DicomDirImport.loadDicomDir(dicomdir, mock(DicomModel.class), true);

      assertEquals(2, opened.unavailable());
      assertEquals("IM1", opened.firstUnavailable());
    } finally {
      dicomdir.delete();
    }
  }

  @Test
  void aPartlyReadDicomdirIsReportedWithWhatWasKept() throws Exception {
    dicomdir.createNewFile();
    List<File> images = List.of(new File("IM1"));
    DicomModel model = mock(DicomModel.class);
    StudyLibrary library = mock(StudyLibrary.class);
    try (MockedStatic<StudyLibrary> statics = mockStatic(StudyLibrary.class);
        MockedConstruction<DicomDirLoader> _ =
            mockConstruction(
                DicomDirLoader.class,
                (loader, _) -> {
                  when(loader.readDicomDir()).thenReturn(List.of(mock(LoadSeries.class)));
                  when(loader.referencedFiles()).thenReturn(images);
                  when(loader.isIncomplete()).thenReturn(true);
                })) {
      statics.when(StudyLibrary::getInstance).thenReturn(library);

      DicomDirImport.loadDicomDir(dicomdir, model, true);

      verify(library).retainReferenced(eq(images), eq(model), eq(1), eq(dicomdir.getName()));
    } finally {
      dicomdir.delete();
    }
  }

  @Test
  void aDicomdirThatGaveNothingLeavesTheCopyToTheWholeDiscRoute() throws Exception {
    dicomdir.createNewFile();
    StudyLibrary library = mock(StudyLibrary.class);
    try (MockedStatic<StudyLibrary> statics = mockStatic(StudyLibrary.class);
        MockedConstruction<DicomDirLoader> _ =
            mockConstruction(
                DicomDirLoader.class,
                (loader, _) -> when(loader.readDicomDir()).thenReturn(List.of()))) {
      statics.when(StudyLibrary::getInstance).thenReturn(library);

      DicomDirImport.loadDicomDir(dicomdir, mock(DicomModel.class), true);

      verify(library, never()).retainReferenced(any(), (DataExplorerModel) any(), anyInt(), any());
    } finally {
      dicomdir.delete();
    }
  }
}
