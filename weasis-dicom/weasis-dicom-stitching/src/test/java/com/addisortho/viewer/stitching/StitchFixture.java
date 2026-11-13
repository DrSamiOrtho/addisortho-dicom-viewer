/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;

/**
 * Three synthetic pieces of one phantom leg with known poses, as a study would hold them, and their
 * stitch. {@link #pieces} gives new pieces over the same pixels each time, as reopening the study
 * after a restart does. Nothing here is a patient's.
 */
final class StitchFixture {

  static final String STUDY = "2.25.100";
  static final String PATIENT_NAME = "Test^Phantom";
  static final String PATIENT_ID = "PHANTOM-ID-1";
  static final Instant SAVED = Instant.parse("2026-11-10T06:30:00Z");
  static final Stitch.Choices CHOICES =
      new Stitch.Choices("AP", "standing", Stitch.Leg.LEFT, SeamMatcher.Band.WHOLE);

  private static final double W = 200;
  private static final double H = 300;

  private final List<Attributes> headers = new ArrayList<>();
  private final List<float[]> pixels = new ArrayList<>();
  private final List<int[]> sizes = new ArrayList<>();

  StitchFixture() {
    this(
        Phantom.leg(300, 900, 21),
        List.of(
            new Phantom.Cut(new Rigid(0, 50, 0), W, H, 0.3, 1, 0),
            new Phantom.Cut(new Rigid(Math.toRadians(0.7), 54, 250), W, H, 0.3, 1.4, 0.2),
            new Phantom.Cut(new Rigid(Math.toRadians(-0.5), 47, 500), W, H, 0.35, 0.8, -0.1)),
        0.01,
        30);
  }

  /** Two pieces of a film of both legs that moved alike: target leg Both can pass on it. */
  static StitchFixture twoLegs() {
    return new StitchFixture(
        Phantom.twoLegs(427, 900, 110, 320, 41),
        List.of(
            new Phantom.Cut(Rigid.IDENTITY, 427, 356, 0.139, 1, 0),
            new Phantom.Cut(new Rigid(Math.toRadians(0.3), 2, 280), 427, 356, 0.139, 1.2, 0)),
        0.02,
        43);
  }

  /**
   * Three pieces of one leg taken close together, so that all three overlap between 210 and 300 mm
   * (the geometry of the knee-to-ankle films of 7 Nov 2026): the two seams' cuts can be dragged
   * past each other.
   */
  static StitchFixture allOverlapping() {
    return new StitchFixture(
        Phantom.leg(300, 900, 21),
        List.of(
            new Phantom.Cut(new Rigid(0, 50, 0), W, H, 0.3, 1, 0),
            new Phantom.Cut(new Rigid(Math.toRadians(0.4), 54, 120), W, H, 0.3, 1.2, 0.1),
            new Phantom.Cut(new Rigid(Math.toRadians(-0.3), 47, 210), W, H, 0.3, 0.9, -0.1)),
        0.01,
        30);
  }

  /** The cuts of {@link #shortThird}: where each piece truly lies on the leg. */
  static final List<Phantom.Cut> SHORT_THIRD =
      List.of(
          new Phantom.Cut(new Rigid(0, 50, 0), W, 300, 0.3, 1, 0),
          new Phantom.Cut(new Rigid(0, 50, 100), W, 200, 0.3, 1, 0),
          new Phantom.Cut(new Rigid(0, 50, 150), W, 50, 0.3, 1, 0));

  /**
   * Three pieces over 0 to 300, 100 to 300 and 150 to 200 mm of one leg: the third is short, so
   * seam 2's cut can lie no lower than 190 mm while seam 1's overlap reaches 290 mm.
   */
  static StitchFixture shortThird() {
    return new StitchFixture(Phantom.leg(300, 900, 21), SHORT_THIRD, 0.01, 30);
  }

  private StitchFixture(Phantom leg, List<Phantom.Cut> cuts, double noise, int seed) {
    for (int i = 0; i < cuts.size(); i++) {
      Phantom.Cut c = cuts.get(i);
      headers.add(header(i, c.spacingMm()));
      pixels.add(leg.piece(c, noise, seed + i));
      int[] size = {
        (int) Math.round(c.widthMm() / c.spacingMm()),
        (int) Math.round(c.heightMm() / c.spacingMm())
      };
      sizes.add(size);
      headers.get(i).setInt(Tag.Columns, VR.US, size[0]);
      headers.get(i).setInt(Tag.Rows, VR.US, size[1]);
    }
  }

  static Attributes header(int i, double spacing) {
    Attributes a = new Attributes();
    a.setString(Tag.PatientName, VR.PN, PATIENT_NAME);
    a.setString(Tag.PatientID, VR.LO, PATIENT_ID);
    a.setString(Tag.StudyInstanceUID, VR.UI, STUDY);
    a.setString(Tag.SeriesInstanceUID, VR.UI, "2.25.10" + i);
    a.setString(Tag.SOPInstanceUID, VR.UI, "2.25.20" + i);
    a.setString(Tag.SOPClassUID, VR.UI, UID.ComputedRadiographyImageStorage);
    a.setString(Tag.Modality, VR.CS, "CR");
    a.setInt(Tag.Rows, VR.US, (int) Math.round(H / spacing));
    a.setInt(Tag.Columns, VR.US, (int) Math.round(W / spacing));
    a.setInt(Tag.SamplesPerPixel, VR.US, 1);
    a.setString(Tag.PhotometricInterpretation, VR.CS, "MONOCHROME2");
    a.setString(Tag.PixelSpacing, VR.DS, Double.toString(spacing), Double.toString(spacing));
    a.setString(Tag.BodyPartExamined, VR.CS, "LEG");
    a.setString(Tag.BurnedInAnnotation, VR.CS, "YES");
    return a;
  }

  int count() {
    return headers.size();
  }

  /** Piece {@code i} anew, its header a copy that a test may change. */
  Piece piece(int i) {
    float[] v = pixels.get(i);
    return new Piece(new Attributes(headers.get(i)), sizes.get(i)[0], sizes.get(i)[1], v::clone);
  }

  /** Piece {@code i} with one pixel changed and everything else the same. */
  Piece pieceWithOnePixelChanged(int i) {
    float[] v = pixels.get(i).clone();
    v[v.length / 2] += 0.25f;
    return new Piece(new Attributes(headers.get(i)), sizes.get(i)[0], sizes.get(i)[1], v::clone);
  }

  /** The pieces anew, in head-to-foot order, not yet read. */
  List<Piece> pieces() {
    List<Piece> out = new ArrayList<>();
    for (int i = 0; i < count(); i++) {
      out.add(piece(i));
    }
    return out;
  }

  /** How Line up sets every seam of a new stitch. */
  static List<SeamRecipe> lineUp(int seams) {
    List<SeamRecipe> out = new ArrayList<>();
    for (int i = 0; i < seams; i++) {
      out.add(SeamRecipe.match(SeamMatcher.Band.WHOLE));
    }
    return out;
  }

  /** The seams the decisions give on these pieces, read first if need be. */
  static List<Seam> seams(List<Piece> pieces, List<SeamRecipe> recipes) throws IOException {
    for (Piece p : pieces) {
      p.prepare();
    }
    List<Seam> out = new ArrayList<>();
    for (int i = 0; i < recipes.size(); i++) {
      out.add(recipes.get(i).compute(pieces.get(i).match(), pieces.get(i + 1).match()));
    }
    return out;
  }

  /** A version of {@code s} as the window would save it, with made-up picture identifiers. */
  static StitchProject capture(Stitch s, List<SeamRecipe> recipes, StitchResume.Lineage lineage) {
    return StitchResume.capture(s, recipes, lineage, SAVED, "2.25.900", "2.25.901");
  }
}
