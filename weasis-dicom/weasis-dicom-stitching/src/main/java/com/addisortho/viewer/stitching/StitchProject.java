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
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One saved version of an editable long-leg stitch: everything needed to open the stitch window
 * again on the same pieces, change it and save a new version. It is the editing state; the
 * flattened Secondary Capture that {@link StitchWriter} writes, and the seam data inside it, are
 * never read back as such. {@link StitchProjectIO} reads and writes it as JSON, schema version
 * {@value #SCHEMA_VERSION}.
 *
 * <h2>Coordinates</h2>
 *
 * <ul>
 *   <li>Each piece has its own frame in millimetres: x to the right and y down as the image is
 *       stored, the origin at the outer corner of the top-left pixel, so pixel (column c, row r)
 *       has its centre at ((c + 0.5) * colMm, (r + 0.5) * rowMm).
 *   <li>The millimetres are the piece's own {@linkplain Source#colMm spacing} on its {@linkplain
 *       Source#basis basis}; they are the size at the bone only as far as that basis is. With basis
 *       {@code NONE} they are not millimetres at all but working units of {@value
 *       Spacing#UNSCALED_MM} per pixel column, and the stitch carries no scale.
 *   <li>A seam's transform takes the lower piece's frame to the upper piece's: p' = R(thetaRad) p +
 *       (txMm, tyMm), with R = [[cos, -sin], [sin, cos]], so a positive angle turns the lower piece
 *       clockwise on the screen. Scale is locked to 1.
 *   <li>The stitch frame is the first piece's frame. Piece k's pose on it is the seams above it
 *       applied in turn: pose(k + 1) = pose(k) after seam k's transform.
 *   <li>A point pair is {fromX, fromY} in the lower piece's frame and {toX, toY} in the upper
 *       piece's. A cut is a height in its upper piece's frame.
 * </ul>
 *
 * <h2>What may change</h2>
 *
 * <ul>
 *   <li><b>Source facts, never edited:</b> {@link #studyInstanceUid} and every {@link Source}. A
 *       source is known by its SOP Instance UID and proved by the hash of its pixels; no file name,
 *       path or patient detail is kept. A project is only reopened on exactly these images.
 *   <li><b>Decisions, which editing changes:</b> the pieces and their order, the {@link #choices}
 *       the user confirmed, each seam's {@link SeamEntry#decision} (the match and band, the
 *       position set by hand, the picked point pairs) and {@link SeamEntry#cutRequestedMm}.
 *   <li><b>Results, made again on reopening and never believed:</b> each seam's {@link
 *       SeamEntry#result} (transform, method, status, residual, uncertainty, the matched points
 *       that drove the fit, the reasons) and {@link SeamEntry#cut}, the {@link #blending} the
 *       compositor used, the {@link #output} grid and {@link #algorithm}. They record what was
 *       saved, so that a reopened seam which does not come out the same is caught ({@link
 *       StitchResume}).
 *   <li><b>Identity, fixed when saved:</b> {@link #projectId}, {@link #versionId}, {@link
 *       #parentVersionId}, {@link #savedAt}. An edit is a new version naming its parent; no version
 *       is ever written over.
 * </ul>
 *
 * @param projectId the same for every version of one stitch (a UUID)
 * @param versionId this version (a UUID)
 * @param parentVersionId the version this one was edited from, or null for the first
 * @param algorithm {@link StitchWriter#SOFTWARE} of the viewer that saved it
 * @param sources the pieces in order, head to foot
 * @param seams seam k joins source k + 1 to source k
 */
record StitchProject(
    String projectId,
    String versionId,
    String parentVersionId,
    Instant savedAt,
    String algorithm,
    String studyInstanceUid,
    List<Source> sources,
    Stitch.Choices choices,
    List<SeamEntry> seams,
    Blending blending,
    Output output) {

  static final String FORMAT = "addisortho-stitch-project";
  static final int SCHEMA_VERSION = 1;

  /** What {@link Source#pixelHash} is taken over: see {@link Piece#pixelHash}. */
  static final String HASH_CONTENT = "modality-values-float32be-v1";

  static final String HASH_ALGORITHM = "SHA-256";

  private static final Pattern UUID_FORM =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern DICOM_UID = Pattern.compile("[0-9][0-9.]{0,63}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

  /**
   * One piece as it was stitched.
   *
   * @param frame always 1: only single-frame images can be pieces
   * @param inverse MONOCHROME1, turned so that bone is bright
   * @param colMm horizontal pixel size, see the coordinates above
   * @param pixelHash {@link Piece#pixelHash}, lower-case hexadecimal
   */
  record Source(
      String sopInstanceUid,
      String sopClassUid,
      String seriesInstanceUid,
      int frame,
      int columns,
      int rows,
      boolean inverse,
      double colMm,
      double rowMm,
      Spacing.Basis basis,
      String pixelHash) {

    static Source of(Piece p) {
      return new Source(
          p.sopInstanceUid(),
          p.sopClassUid(),
          p.header.getString(org.dcm4che3.data.Tag.SeriesInstanceUID, ""),
          1,
          p.width,
          p.height,
          p.inverse,
          p.spacing.colMm(),
          p.spacing.rowMm(),
          p.spacing.basis(),
          p.pixelHash());
    }
  }

  /**
   * One seam.
   *
   * @param decision how the seam was set
   * @param cutRequestedMm where the user dragged the cut, in the upper piece's frame; NaN when the
   *     cut is found automatically
   * @param result the seam as it was saved
   * @param cut the cut as it was saved: the height actually used, and why
   */
  record SeamEntry(SeamRecipe decision, double cutRequestedMm, Seam result, Cut.Row cut) {}

  /** The compositor's blending when this was saved; fixed by the algorithm, not a choice. */
  record Blending(double featherMm, double toneMm, double toneSigmaMm) {

    static Blending current() {
      return new Blending(Stitch.FEATHER_MM, Compositor.TONE_MM, Compositor.TONE_SIGMA_MM);
    }
  }

  /**
   * The flattened picture saved with this version: a reference for lineage, never editing state.
   *
   * @param unverified saved through Save as unverified draft, because a seam had failed
   * @param mmPerPixel the output pixel size, or NaN when the picture carries no scale
   * @param rows the image rows, without the label strip above them
   */
  record Output(
      String sopInstanceUid,
      String seriesInstanceUid,
      boolean unverified,
      double mmPerPixel,
      int columns,
      int rows) {

    /** Stands for the picture's identifiers while a version is checked before it is written. */
    static final String PENDING = "0";
  }

  StitchProject {
    sources = List.copyOf(sources);
    seams = List.copyOf(seams);
  }

  /** This version, naming the picture that was saved with it. */
  StitchProject withOutput(String sopInstanceUid, String seriesInstanceUid) {
    return new StitchProject(
        projectId,
        versionId,
        parentVersionId,
        savedAt,
        algorithm,
        studyInstanceUid,
        sources,
        choices,
        seams,
        blending,
        new Output(
            sopInstanceUid,
            seriesInstanceUid,
            output.unverified(),
            output.mmPerPixel(),
            output.columns(),
            output.rows()));
  }

  boolean anyFailed() {
    return seams.stream().anyMatch(s -> s.result().status() == Seam.Status.FAILED);
  }

  /**
   * Refuses a project that cannot be one: the reader's and the writer's common gate. It also holds
   * the save rules as the record shows them: two to four pieces, never scaled with unscaled, a
   * failed seam only in an unverified draft, and an unverified draft never with a scale.
   */
  void check() throws StitchProjectException {
    require(matches(UUID_FORM, projectId), "the project identifier is not a UUID");
    require(matches(UUID_FORM, versionId), "the version identifier is not a UUID");
    require(
        parentVersionId == null || matches(UUID_FORM, parentVersionId),
        "the parent version identifier is not a UUID");
    require(!versionId.equals(parentVersionId), "a version cannot be its own parent");
    require(savedAt != null, "the time it was saved is missing");
    require(algorithm != null && !algorithm.isBlank(), "the algorithm version is missing");
    require(matches(DICOM_UID, studyInstanceUid), "the study identifier is not a DICOM UID");
    checkSources();
    require(choices != null, "the confirmations are missing");
    require(notBlank(choices.projection()), "the projection is not confirmed");
    require(notBlank(choices.position()), "the position is not confirmed");
    require(choices.leg() != null, "the target leg is not confirmed");
    require(choices.band() != null, "the part of the image to match on is not confirmed");
    require(
        seams.size() == sources.size() - 1,
        "%d pieces need %d seams, not %d"
            .formatted(sources.size(), sources.size() - 1, seams.size()));
    for (int i = 0; i < seams.size(); i++) {
      checkSeam(i + 1, seams.get(i));
    }
    require(
        blending != null
            && positive(blending.featherMm())
            && positive(blending.toneMm())
            && positive(blending.toneSigmaMm()),
        "the blending settings are missing or impossible");
    checkOutput();
  }

  private void checkSources() throws StitchProjectException {
    require(
        sources.size() >= 2 && sources.size() <= 4,
        "a stitch has two to four pieces, not %d".formatted(sources.size()));
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < sources.size(); i++) {
      Source s = sources.get(i);
      String piece = "piece %d: ".formatted(i + 1);
      require(s != null, piece + "missing");
      require(matches(DICOM_UID, s.sopInstanceUid()), piece + "its image identifier is not a UID");
      require(matches(DICOM_UID, s.sopClassUid()), piece + "its image class is not a UID");
      require(matches(DICOM_UID, s.seriesInstanceUid()), piece + "its series is not a UID");
      require(seen.add(s.sopInstanceUid()), piece + "the same image is used twice");
      require(s.frame() == 1, piece + "only frame 1 of a single-frame image can be a piece");
      require(s.columns() >= 64 && s.rows() >= 64, piece + "too small to be a piece");
      require(positive(s.colMm()) && positive(s.rowMm()), piece + "its pixel size is impossible");
      require(s.basis() != null, piece + "the basis of its pixel size is missing");
      require(matches(SHA256, s.pixelHash()), piece + "the hash of its pixels is not SHA-256");
    }
    boolean scaled = sources.stream().anyMatch(s -> s.basis() != Spacing.Basis.NONE);
    boolean unscaled = sources.stream().anyMatch(s -> s.basis() == Spacing.Basis.NONE);
    require(!(scaled && unscaled), "pieces with and without pixel spacing cannot be joined");
  }

  private static void checkSeam(int n, SeamEntry e) throws StitchProjectException {
    String seam = "seam %d: ".formatted(n);
    require(e != null && e.decision() != null, seam + "how it was set is missing");
    SeamRecipe d = e.decision();
    require(d.start() == null || finite(d.start()), seam + "the start position is not a number");
    require(d.movedTo() == null || finite(d.movedTo()), seam + "the hand position is not a number");
    require(d.points().stream().allMatch(StitchProject::finite), seam + "a point is not a number");
    require(
        Double.isNaN(e.cutRequestedMm()) || Double.isFinite(e.cutRequestedMm()),
        seam + "the cut is not a number");
    Seam r = e.result();
    require(r != null && r.transform() != null, seam + "its saved result is missing");
    require(finite(r.transform()), seam + "its transform is not a number");
    require(r.method() != null && r.status() != null, seam + "its method or status is missing");
    require(r.pairs().stream().allMatch(StitchProject::finite), seam + "a point is not a number");
    require(
        e.cut() != null && Double.isFinite(e.cut().yMm()) && e.cut().note() != null,
        seam + "its saved cut is missing");
  }

  private void checkOutput() throws StitchProjectException {
    require(output != null, "the saved picture's reference is missing");
    require(matches(DICOM_UID, output.sopInstanceUid()), "the saved picture's UID is not one");
    require(
        matches(DICOM_UID, output.seriesInstanceUid()), "the saved picture's series is not one");
    require(output.columns() > 0 && output.rows() > 0, "the saved picture's size is impossible");
    require(
        output.unverified() == anyFailed(),
        output.unverified()
            ? "it says unverified draft although no seam failed"
            : "it says an ordinary draft although a seam failed: a failed seam is only ever saved"
                + " as an unverified draft");
    Spacing.Basis first = sources.get(0).basis();
    boolean scale =
        !output.unverified()
            && first != Spacing.Basis.NONE
            && sources.stream().allMatch(s -> s.basis() == first);
    require(
        Double.isNaN(output.mmPerPixel()) || positive(output.mmPerPixel()),
        "the saved picture's pixel size is impossible");
    require(
        scale == !Double.isNaN(output.mmPerPixel()),
        scale
            ? "the saved picture's pixel size is missing"
            : "it gives a pixel size although the stitch carries no scale");
  }

  private static void require(boolean ok, String problem) throws StitchProjectException {
    if (!ok) {
      throw new StitchProjectException(Kind.MALFORMED, "Not a usable stitch project: " + problem);
    }
  }

  private static boolean matches(Pattern pattern, String value) {
    return value != null && pattern.matcher(value).matches();
  }

  private static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static boolean positive(double v) {
    return Double.isFinite(v) && v > 0;
  }

  private static boolean finite(Rigid r) {
    return Double.isFinite(r.theta()) && Double.isFinite(r.tx()) && Double.isFinite(r.ty());
  }

  private static boolean finite(Rigid.Pair p) {
    return Double.isFinite(p.fx())
        && Double.isFinite(p.fy())
        && Double.isFinite(p.tx())
        && Double.isFinite(p.ty());
  }
}
