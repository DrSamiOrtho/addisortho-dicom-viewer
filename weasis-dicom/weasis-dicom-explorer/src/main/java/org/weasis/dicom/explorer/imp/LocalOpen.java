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

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.library.StudyLibrary;

/**
 * Opens files that were dropped on the application or handed to it by the system: in the viewer
 * from where they lie, which is quick, and a copy into the library in the background, so that they
 * can still be opened after a disc is ejected or an archive is moved.
 */
public final class LocalOpen {

  @FunctionalInterface
  interface Viewer {
    void load(File[] files, boolean recursive);
  }

  @FunctionalInterface
  interface Library {
    void retain(List<File> files, boolean recursive);
  }

  private LocalOpen() {}

  public static void open(File[] files, boolean recursive, DataExplorerModel model) {
    open(
        files,
        recursive,
        (f, r) ->
            DicomModel.LOADING_EXECUTOR.execute(
                new LoadLocalDicom(f, r, model, OpeningViewer.ALL_PATIENTS)
                    .withArchiveFailuresReportedByLibrary()),
        (f, r) ->
            StudyLibrary.getInstance()
                .retainDropped(f, r, GuiUtils.getUICore().getApplicationWindow(), model));
  }

  static void open(File[] files, boolean recursive, Viewer viewer, Library library) {
    if (files == null || files.length == 0) {
      return;
    }
    viewer.load(files, recursive);
    List<File> sources = Arrays.stream(files).filter(Objects::nonNull).toList();
    if (!sources.isEmpty()) {
      library.retain(sources, recursive);
    }
  }
}
