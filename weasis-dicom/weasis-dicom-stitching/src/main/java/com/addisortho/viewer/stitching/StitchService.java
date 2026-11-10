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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.swing.SwingUtilities;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.weasis.dicom.explorer.library.StudyLibrary;
import org.weasis.dicom.explorer.library.StudyStore;
import org.weasis.opencv.data.PlanarImage;

/** The viewer side: which images of the study can be pieces, and saving the stitch into it. */
final class StitchService {

  private static final Logger LOGGER = LoggerFactory.getLogger(StitchService.class);
  static final Set<String> MODALITIES = Set.of("CR", "DX", "DR", "RG");
  static final double LONG_MM = 600;

  private StitchService() {}

  /** An image of the study that can be a piece, with anything the user should know about it. */
  record Candidate(Piece piece, List<String> notes, String fileName) {}

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
        out.add(new Candidate(p, notes(p), fileName(image.getMediaReader().getUri())));
      }
    }
    return new Study(model, out, excluded);
  }

  /**
   * The name the file was opened under, or empty when there is none worth showing: a name of only
   * digits and dots is a DICOM UID or a machine's counter and says nothing about the image.
   */
  static String fileName(URI uri) {
    String path = uri == null ? null : uri.getPath();
    if (path == null) {
      return "";
    }
    String name = path.substring(path.lastIndexOf('/') + 1);
    return name.replaceFirst("(?i)\\.dcm$", "").matches("[0-9.]*") ? "" : name;
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

  /**
   * Saves the stitch for good in the study library of this computer and, where {@code projects} are
   * kept, as an editable version.
   */
  static Saved save(
      StitchProjects projects,
      Stitch s,
      List<SeamRecipe> recipes,
      StitchResume.Lineage lineage,
      Compositor.Progress progress)
      throws IOException {
    return save(StudyLibrary.getInstance().store(), projects, s, recipes, lineage, progress);
  }

  /**
   * Writes the stitch into {@code store}. When this returns it is on the disk, read back and in the
   * catalog, whatever then becomes of opening it.
   */
  static StudyStore.SavedDerived save(StudyStore store, Stitch s, Compositor.Progress progress)
      throws IOException {
    return store.saveDerived(file -> writeComplete(s, file, progress));
  }

  /**
   * Where editable stitch projects are kept: the study library of this computer, beside the
   * pictures ({@link LibraryProjectStore}). Empty would switch editing off: the window then saves
   * the picture only and offers no Edit stitch.
   */
  static Optional<ProjectStore> projectStore() {
    return Optional.of(new LibraryProjectStore(() -> StudyLibrary.getInstance().store()));
  }

  /**
   * What one save left: the picture, and the editable version saved with it.
   *
   * @param version null when no projects are kept, or when the version is not kept
   * @param versionProblem what went wrong saving the version although the picture was saved, else
   *     null. With {@code version} null it was not kept; with a version it is kept and can be
   *     edited, but the store failed after writing it
   */
  record Saved(StudyStore.SavedDerived picture, StitchProject version, String versionProblem) {}

  /**
   * Saves the picture, then the stitch as a new version of its project so it can be edited later.
   * Neither replaces anything saved before.
   *
   * <p>Everything that can be known beforehand is checked before a byte is written: a failed seam
   * goes out only as an unverified draft, and where versions are kept the version must be one the
   * project format accepts, under a parent that is saved. A stitch refused here leaves no picture.
   * The two stores cannot be written as one, though: when the picture is written and the project
   * store then fails, the picture stands on its own, as every stitch did before projects, and
   * {@link Saved#versionProblem} says why it cannot be edited later.
   *
   * @param projects null to save the picture only
   * @param recipes how each seam of {@code s} was set
   * @throws IOException when nothing was saved
   */
  static Saved save(
      StudyStore store,
      StitchProjects projects,
      Stitch s,
      List<SeamRecipe> recipes,
      StitchResume.Lineage lineage,
      Compositor.Progress progress)
      throws IOException {
    if (s.anyFailed() && !s.unverified) {
      throw new IOException(
          "%s failed, so the stitch can only be saved as an unverified draft"
              .formatted(s.failedSeams()));
    }
    StitchProject planned = null;
    if (projects != null) {
      planned =
          StitchResume.capture(
              s,
              recipes,
              lineage,
              Instant.now(),
              StitchProject.Output.PENDING,
              StitchProject.Output.PENDING);
      planned.check();
      projects.checkLineage(planned);
    }
    StudyStore.SavedDerived picture = save(store, s, progress);
    if (planned == null) {
      return new Saved(picture, null, null);
    }
    StitchProject version = null;
    try {
      version = planned.withOutput(picture.instance().sopUid(), picture.instance().seriesUid());
      projects.save(version);
      return new Saved(picture, version, null);
    } catch (IOException | RuntimeException e) {
      // A store can fail after the version is whole; then it is kept, and is said to be.
      boolean kept = version != null && projects.kept(version);
      // Its message can hold a path: for the user, not for the log.
      LOGGER.error(
          "The stitch project was not saved cleanly (kept: {}): {}",
          kept,
          e.getClass().getSimpleName());
      return new Saved(picture, kept ? version : null, String.valueOf(e.getMessage()));
    }
  }

  private static void writeComplete(Stitch s, Path file, Compositor.Progress progress)
      throws IOException {
    Attributes header = StitchWriter.write(s, file, progress);
    long pixels = (long) header.getInt(Tag.Rows, 0) * header.getInt(Tag.Columns, 0) * 2;
    if (pixels == 0 || Files.size(file) < pixels) {
      throw new IOException("the stitch was not written completely");
    }
  }

  /**
   * Loads the saved stitch into the study, then opens it in a viewer. The saved file is never
   * touched here: if this fails, the stitch is still saved and {@code notOpened} tells the user.
   */
  static void loadAndOpen(DicomModel model, Path file, String seriesUid, Runnable notOpened) {
    DicomModel.LOADING_EXECUTOR.execute(
        new LoadLocalDicom(new File[] {file.toFile()}, false, model, OpeningViewer.NONE));
    // the loader is single-threaded: this runs once the file is in the model
    DicomModel.LOADING_EXECUTOR.execute(
        () -> openLoaded(model.getSeriesNode(seriesUid), model, notOpened));
  }

  /**
   * @param loaded the series the loader made of the saved file, or null when it made none
   */
  static void openLoaded(Object loaded, DicomModel model, Runnable notOpened) {
    if (loaded instanceof DicomSeries series) {
      SwingUtilities.invokeLater(
          () -> {
            try {
              ViewerPluginBuilder.openInDefaultViewer(series, model, ViewerOpenOptions.defaults());
            } catch (RuntimeException e) {
              LOGGER.error("Cannot open the saved stitch: {}", e.getClass().getSimpleName());
              notOpened.run();
            }
          });
    } else {
      SwingUtilities.invokeLater(notOpened);
    }
  }
}
