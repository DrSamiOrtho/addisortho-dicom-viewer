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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;
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

      verify(library).retainReferenced(eq(images), eq(model));
      assertEquals(1, loaders.constructed().size());
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

      verify(library, never()).retainReferenced(any(), (DataExplorerModel) any());
    } finally {
      dicomdir.delete();
    }
  }
}
