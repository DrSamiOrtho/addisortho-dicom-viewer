/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Set;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.image.ViewTransferHandler;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.library.StudyLibrary;

/** The one templating server of the viewer, started on the first Template press. */
final class TemplatingService {

  private static final Logger LOGGER = LoggerFactory.getLogger(TemplatingService.class);

  /** The page's own rule: a plain X-ray (templating/web/app.js, openFromArchive). */
  static final Set<String> MODALITIES = Set.of("CR", "DX", "DR", "RG");

  private static TemplatingServer server;

  private TemplatingService() {}

  static synchronized TemplatingServer server() throws IOException {
    if (server == null) {
      server =
          new TemplatingServer(
              TemplatingServer.PREFERRED_PORT,
              PageFiles.load(TemplatingService.class.getClassLoader(), "templating-web/"),
              AppProperties.buildAccessibleTempDirectory("templating"),
              TemplatingService::intoExplorer,
              TemplatingService::intoLibrary,
              Clock.systemUTC());
    }
    return server;
  }

  static synchronized void stop() {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  /** Why this image cannot be templated, or null when it can. */
  static String refusal(MediaSeries<DicomImageElement> series, DicomImageElement image) {
    if (series == null || image == null) {
      return Messages.getString("TemplatingToolBar.noImage");
    }
    String modality = TagD.getTagValue(series, Tag.Modality, String.class);
    if (modality == null || !MODALITIES.contains(modality)) {
      return Messages.getString("TemplatingToolBar.notXray")
          .formatted(modality == null ? "?" : modality);
    }
    // a stitch saved although a seam failed says so in its comments: nothing may be measured on it
    String comments = TagD.getTagValue(image, Tag.ImageComments, String.class);
    String description = TagD.getTagValue(series, Tag.SeriesDescription, String.class);
    if ((comments != null && comments.contains("UNVERIFIED"))
        || (description != null && description.contains("UNVERIFIED"))) {
      return Messages.getString("TemplatingToolBar.unverifiedStitch");
    }
    return null;
  }

  /**
   * Copies the image's DICOM file for the page (with file meta information, which a file from a CD
   * may lack) and returns the address for it of the Quick or the full page.
   */
  static URI handOver(MediaSeries<DicomImageElement> series, DicomImageElement image, boolean quick)
      throws IOException {
    TemplatingServer s = server();
    Path dir = AppProperties.buildAccessibleTempDirectory("templating", "xray");
    Path copy = Files.createTempFile(dir, "xray-", ".dcm");
    if (!image.saveToFile(copy.toFile())) {
      throw new IOException(Messages.getString("TemplatingToolBar.noFile"));
    }
    XrayFile.withFileMeta(copy);
    Object model = series.getTagValue(TagW.ExplorerModel);
    URI page = s.handOver(copy, model);
    if (quick) {
      page = TemplatingServer.quickPage(page);
    }
    // the page draws the viewer's footer on its saved picture unless the preference turns it off
    return ViewTransferHandler.isFooterEnabled() ? page : URI.create(page + "&footer=0");
  }

  /**
   * Keeps a saved plan in the study library of this computer, in the X-ray's study, as stitches
   * are: the templating work folder is emptied when the viewer closes.
   */
  private static Path intoLibrary(Path plan) throws IOException {
    return StudyLibrary.getInstance()
        .store()
        .saveDerived(file -> Files.copy(plan, file, StandardCopyOption.REPLACE_EXISTING))
        .instance()
        .file();
  }

  /** Puts a saved plan into the explorer, in the X-ray's patient and study. */
  private static void intoExplorer(Handover h, Handover.Plan plan) {
    if (h.owner() instanceof DicomModel model) {
      DicomModel.LOADING_EXECUTOR.execute(
          new LoadLocalDicom(new File[] {plan.file().toFile()}, false, model, OpeningViewer.NONE));
    } else {
      LOGGER.warn("Templating plan {} saved without an explorer to show it in", plan.file());
    }
  }
}
