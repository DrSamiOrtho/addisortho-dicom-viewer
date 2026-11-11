/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import com.addisortho.viewer.stitching.StitchProjectException.Kind;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Between the stitch window and a saved {@link StitchProject}: {@link #capture} records the
 * window's stitch as a version, and {@link #sources} with {@link #recheck} bring a version back as
 * a stitch that can be edited.
 *
 * <p>Reopening believes none of the saved results. The pieces must be the very images that were
 * stitched, all of them, or nothing is reopened. Every seam is then made again from its decision by
 * the same matcher and gates as a new stitch. A seam that does not come out as it was saved is held
 * as failed where it was saved: reopening can lower a seam's status, never raise it, and never
 * moves a piece without saying so.
 */
final class StitchResume {

  /**
   * A reopened seam counts as the saved one when no corner of the lower piece lies farther than
   * this from where the saved seam put it: a third of the finest output pixel.
   */
  static final double REPRODUCED_MM = 0.05;

  /** A cut found this far from where it was saved is told to the user. */
  static final double CUT_MOVED_MM = 0.5;

  private StitchResume() {}

  /**
   * Whose version the next save is.
   *
   * @param projectId null to begin a new project
   * @param parentVersionId the version the window's stitch was reopened from or last saved as
   */
  record Lineage(String projectId, String parentVersionId) {
    static final Lineage NEW = new Lineage(null, null);
  }

  /**
   * A version brought back into the window.
   *
   * @param cuts per seam, where the user had put the cut; NaN for an automatic one
   * @param notes what the user must know: seams held as failed, a different algorithm, a moved cut
   */
  record Resumed(
      List<Piece> pieces,
      Stitch.Choices choices,
      List<SeamRecipe> recipes,
      List<Seam> seams,
      double[] cuts,
      List<String> notes) {

    Stitch stitch() {
      return new Stitch(pieces, seams, choices, cuts);
    }
  }

  /**
   * The stitch as a new version of its project.
   *
   * @param recipes how each seam of {@code s} was set
   * @param outputSopUid the flattened picture saved from {@code s}
   */
  static StitchProject capture(
      Stitch s,
      List<SeamRecipe> recipes,
      Lineage lineage,
      Instant savedAt,
      String outputSopUid,
      String outputSeriesUid) {
    if (recipes.size() != s.seams.size()) {
      throw new IllegalArgumentException("one decision for each seam");
    }
    List<StitchProject.SeamEntry> seams = new ArrayList<>();
    for (int i = 0; i < s.seams.size(); i++) {
      seams.add(
          new StitchProject.SeamEntry(recipes.get(i), s.requestedCut(i), s.seams.get(i), s.cut(i)));
    }
    return new StitchProject(
        lineage.projectId() == null ? UUID.randomUUID().toString() : lineage.projectId(),
        UUID.randomUUID().toString(),
        lineage.parentVersionId(),
        savedAt,
        StitchWriter.SOFTWARE,
        s.pieces.get(0).header.getString(org.dcm4che3.data.Tag.StudyInstanceUID, ""),
        s.pieces.stream().map(StitchProject.Source::of).toList(),
        s.choices,
        seams,
        StitchProject.Blending.current(),
        new StitchProject.Output(
            outputSopUid,
            outputSeriesUid,
            s.unverified,
            s.hasScale() ? s.outputMm() : Double.NaN,
            s.columns(),
            s.rows()));
  }

  /**
   * The project's pieces, in its order, found among the images that are open. Refuses unless every
   * one is there exactly once, still acceptable as a piece, and of the saved size and spacing: no
   * other image is ever taken in a missing one's place, and a stitch is never reopened on some of
   * its pieces.
   */
  static List<Piece> sources(StitchProject p, List<Piece> available) throws StitchProjectException {
    List<Piece> out = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    int n = p.sources().size();
    for (int i = 0; i < n; i++) {
      StitchProject.Source s = p.sources().get(i);
      String piece = "piece %d of %d".formatted(i + 1, n);
      List<Piece> same =
          available.stream().filter(a -> a.sopInstanceUid().equals(s.sopInstanceUid())).toList();
      if (same.isEmpty()) {
        problems.add(piece + " is not among the images open in this study");
      } else if (same.size() > 1) {
        problems.add(piece + " is here more than once, and the copies cannot be told apart");
      } else {
        Piece found = same.get(0);
        String changed = changed(s, found);
        if (changed != null) {
          problems.add(piece + " has changed since the stitch was saved: " + changed);
        }
        out.add(found);
      }
    }
    refuse(problems);
    return out;
  }

  private static String changed(StitchProject.Source s, Piece found) {
    String refusal = StitchService.refusal(found.header);
    if (refusal != null) {
      return "it can no longer be a piece (" + refusal + ")";
    }
    if (!found.sopClassUid().equals(s.sopClassUid())) {
      return "it is another kind of image";
    }
    if (found.width != s.columns() || found.height != s.rows() || found.inverse != s.inverse()) {
      return "its size or polarity differs";
    }
    if (found.spacing.basis() != s.basis()
        || !close(found.spacing.colMm(), s.colMm())
        || !close(found.spacing.rowMm(), s.rowMm())) {
      return "its pixel spacing differs";
    }
    return null;
  }

  private static boolean close(double a, double b) {
    return Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
  }

  private static void refuse(List<String> problems) throws StitchProjectException {
    if (!problems.isEmpty()) {
      throw new StitchProjectException(
          Kind.SOURCES,
          "The stitch cannot be reopened for editing, because its source images are not all here"
              + " and unchanged: "
              + String.join("; ", problems)
              + ". Nothing was put in their place.",
          problems);
    }
  }

  /**
   * Reads the pieces {@link #sources} found, proves each by the hash of its pixels, and makes every
   * seam again from its decision.
   *
   * @throws StitchProjectException when a piece's pixels are not the ones that were stitched
   * @throws CancellationException when {@code progress} says stop
   */
  static Resumed recheck(StitchProject p, List<Piece> pieces, Compositor.Progress progress)
      throws IOException {
    int n = pieces.size();
    int steps = 2 * n - 1;
    List<String> problems = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      step(progress, i, steps);
      pieces.get(i).prepare();
      if (!p.sources().get(i).pixelHash().equals(pieces.get(i).pixelHash())) {
        problems.add(
            "piece %d of %d has changed since the stitch was saved: its pixels differ"
                .formatted(i + 1, n));
      }
    }
    refuse(problems);

    List<String> notes = new ArrayList<>();
    if (!StitchWriter.SOFTWARE.equals(p.algorithm())
        || !StitchProject.Blending.current().equals(p.blending())) {
      notes.add(
          "This stitch was saved by \"%s\"; this viewer is \"%s\". Every seam was checked again by"
                  .formatted(p.algorithm(), StitchWriter.SOFTWARE)
              + " this viewer, and the picture may be blended differently.");
    }
    List<Seam> seams = new ArrayList<>();
    List<SeamRecipe> recipes = new ArrayList<>();
    double[] cuts = new double[n - 1];
    for (int i = 0; i < n - 1; i++) {
      step(progress, n + i, steps);
      StitchProject.SeamEntry entry = p.seams().get(i);
      Seam now = entry.decision().compute(pieces.get(i).match(), pieces.get(i + 1).match());
      Seam kept = reproduced(entry.result(), now, pieces.get(i + 1));
      if (kept != now) {
        notes.add("Seam %d: %s".formatted(i + 1, kept.reasons().get(0)));
      }
      seams.add(kept);
      recipes.add(entry.decision());
      cuts[i] = entry.cutRequestedMm();
    }
    Resumed resumed = new Resumed(List.copyOf(pieces), p.choices(), recipes, seams, cuts, notes);
    Stitch stitch = resumed.stitch();
    for (int i = 0; i < n - 1; i++) {
      double saved = p.seams().get(i).cut().yMm();
      double found = stitch.cut(i).yMm();
      if (Math.abs(found - saved) > CUT_MOVED_MM) {
        notes.add(
            String.format(
                Locale.ROOT,
                "Seam %d: the cut is now at %.1f mm of its upper piece; it was saved at %.1f mm.",
                i + 1,
                found,
                saved));
      }
    }
    step(progress, steps, steps);
    return resumed;
  }

  /**
   * {@code now} when it is the saved seam made again; otherwise the saved position, failed, with
   * the reason first.
   */
  private static Seam reproduced(Seam saved, Seam now, Piece lower) {
    double apart = apart(saved.transform(), now.transform(), lower.widthMm(), lower.heightMm());
    if (now.status() == saved.status()
        && now.method() == saved.method()
        && apart <= REPRODUCED_MM) {
      return now;
    }
    List<String> reasons = new ArrayList<>();
    reasons.add(
        String.format(
            Locale.ROOT,
            "reopened, this seam did not come out as it was saved (saved: %s, %s; checked again"
                + " now: %s, %s, %.2f mm away). It is held as failed where it was saved: open it"
                + " and press Automatic match again or Refine automatically from here, or fit"
                + " matching points",
            saved.method().word,
            saved.status().word,
            now.method().word,
            now.status().word,
            apart));
    if (now.status() == Seam.Status.FAILED && !now.reasons().isEmpty()) {
      reasons.add("checked again now: " + now.reasons().get(0));
    }
    return Seam.failed(saved.transform(), saved.method(), reasons);
  }

  /** The farthest any corner of the lower piece lies between two seams, in mm. */
  static double apart(Rigid a, Rigid b, double widthMm, double heightMm) {
    double worst = 0;
    for (double[] c : new double[][] {{0, 0}, {widthMm, 0}, {0, heightMm}, {widthMm, heightMm}}) {
      double[] p = a.apply(c[0], c[1]);
      double[] q = b.apply(c[0], c[1]);
      worst = Math.max(worst, Math.hypot(p[0] - q[0], p[1] - q[1]));
    }
    return worst;
  }

  private static void step(Compositor.Progress progress, int done, int total) {
    if (!progress.rows(done, total)) {
      throw new CancellationException();
    }
  }
}
