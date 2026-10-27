/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.ViewerOpenOptions;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.opencv.data.PlanarImage;

/** The viewer side: which images of the study can be pieces, and saving the stitch into it. */
final class StitchService {

  static final Set<String> MODALITIES = Set.of("CR", "DX", "DR", "RG");
  static final double LONG_MM = 600;

  private StitchService() {}

  /** An image of the study that can be a piece, with anything the user should know about it. */
  record Candidate(Piece piece, List<String> notes) {}

  /** Eligible images and the images left out, with why. */
  record Study(DicomModel model, List<Candidate> candidates, List<String> excluded) {}

  static Study study(MediaSeries<DicomImageElement> selected) {
    if (selected == null
        || !(selected.getTagValue(TagW.ExplorerModel) instanceof DicomModel model)) {
      return null;
    }
    MediaSeriesGroup study = model.getParent(selected, DicomModel.study);
    if (study == null) {
      return null;
    }
    List<Candidate> out = new ArrayList<>();
    List<String> excluded = new ArrayList<>();
    for (MediaSeriesGroup g : model.getChildren(study)) {
      if (!(g instanceof DicomSeries series)) {
        continue;
      }
      for (DicomImageElement image : series.getSortedMedias(null)) {
        Attributes h = image.getMediaReader().getDicomObject();
        if (h == null) {
          continue;
        }
        String why = refusal(h);
        if (why != null) {
          if (MODALITIES.contains(h.getString(Tag.Modality, ""))) {
            excluded.add(describe(h) + ": " + why);
          }
          continue;
        }
        Piece p = new Piece(h, h.getInt(Tag.Columns, 0), h.getInt(Tag.Rows, 0), () -> read(image));
        out.add(new Candidate(p, notes(p)));
      }
    }
    return new Study(model, out, excluded);
  }

  /** Why an image cannot be a piece, or null when it can. */
  static String refusal(Attributes h) {
    if (!MODALITIES.contains(h.getString(Tag.Modality, ""))) {
      return "not a plain X-ray";
    }
    if (h.getInt(Tag.NumberOfFrames, 1) > 1) {
      return "more than one frame";
    }
    if (h.getInt(Tag.Rows, 0) < 64 || h.getInt(Tag.Columns, 0) < 64) {
      return "too small";
    }
    if (h.getInt(Tag.SamplesPerPixel, 1) != 1) {
      return "a colour image";
    }
    if (isStitched(h)) {
      return "already a stitched image (the machine's or this viewer's own)";
    }
    return null;
  }

  /** Stitched by the machine (its ImageType or derivation says so) or by this viewer. */
  static boolean isStitched(Attributes h) {
    String[] type = h.getStrings(Tag.ImageType);
    String text =
        String.join(
                " ",
                type == null ? "" : String.join("\\", type),
                h.getString(Tag.DerivationDescription, ""),
                h.getString(Tag.SecondaryCaptureDeviceSoftwareVersions, ""))
            .toUpperCase(Locale.ROOT);
    return text.contains("STITCH");
  }

  private static List<String> notes(Piece p) {
    List<String> notes = new ArrayList<>();
    if (p.spacing.hasScale() && p.heightMm() > LONG_MM) {
      notes.add("already over 600 mm tall: check it is not a long film on its own");
    }
    notes.add(p.spacing.note());
    Spacing.disagreement(p.header).ifPresent(notes::add);
    if (p.header.contains(Tag.VOILUTSequence)) {
      // phase 1 writes a linear window; a VOI LUT curve is not reproduced (Astra, code review P2)
      notes.add(
          "has a VOI LUT: the stitch uses a straight window instead, so its grey look may differ;"
              + " check that the bone edges are visible");
    }
    return notes;
  }

  static String describe(Attributes h) {
    String d = h.getString(Tag.SeriesDescription, "");
    return (d.isEmpty() ? "series " + h.getString(Tag.SeriesNumber, "?") : d)
        + ", image "
        + h.getString(Tag.InstanceNumber, "?");
  }

  /** The image's modality values as floats, read outside the viewer's image cache. */
  private static float[] read(DicomImageElement image) throws IOException {
    PlanarImage img = image.getUncachedModalityLutImage(null);
    if (img == null) {
      throw new IOException("cannot read the image " + image);
    }
    Mat f = new Mat();
    try {
      img.toMat().convertTo(f, CvType.CV_32F);
      float[] d = new float[img.width() * img.height()];
      f.get(0, 0, d);
      return d;
    } finally {
      f.release();
      img.release(); // owned by this call (getUncachedModalityLutImage), never the cache's
    }
  }

  /** Writes the stitch to the viewer's temporary folder. */
  static Path save(Stitch s, Compositor.Progress progress) throws IOException {
    Path dir = AppProperties.buildAccessibleTempDirectory("stitching");
    Path file = Files.createTempFile(dir, "stitch-", ".dcm");
    try {
      StitchWriter.write(s, file, progress);
    } catch (IOException | RuntimeException e) {
      Files.deleteIfExists(file);
      throw e;
    }
    return file;
  }

  /** Loads the saved stitch into the study, then opens it in a viewer. */
  static void loadAndOpen(DicomModel model, Path file, String seriesUid) {
    DicomModel.LOADING_EXECUTOR.execute(
        new LoadLocalDicom(new File[] {file.toFile()}, false, model, OpeningViewer.NONE));
    // the loader is single-threaded: this runs once the file is in the model
    DicomModel.LOADING_EXECUTOR.execute(
        () -> {
          if (model.getSeriesNode(seriesUid) instanceof DicomSeries series) {
            SwingUtilities.invokeLater(
                () ->
                    ViewerPluginBuilder.openInDefaultViewer(
                        series, model, ViewerOpenOptions.defaults()));
          }
        });
  }
}
