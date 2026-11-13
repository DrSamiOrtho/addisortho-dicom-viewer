/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.stitching.StitchProjectException.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StitchProjectIOTest {

  private static StitchProject project;
  private static byte[] document;

  /**
   * A stitch that uses every part of the format: an automatic seam then moved by hand, a seam from
   * picked points, and a cut dragged by hand.
   */
  @BeforeAll
  static void build() throws IOException {
    StitchFixture f = new StitchFixture();
    List<Piece> pieces = f.pieces();
    List<Seam> auto = StitchFixture.seams(pieces, StitchFixture.lineUp(2));
    Rigid t = auto.get(1).transform();
    List<Rigid.Pair> picked = new ArrayList<>();
    for (double[] q : new double[][] {{40, 10}, {110, 25}, {160, 12}, {80, 40}}) {
      double[] to = t.apply(q[0], q[1]);
      picked.add(new Rigid.Pair(q[0], q[1], to[0], to[1]));
    }
    Rigid nudged = Rigid.shift(0.3, -0.2).after(auto.get(0).transform());
    List<SeamRecipe> recipes =
        List.of(SeamRecipe.match(SeamMatcher.Band.WHOLE).moved(nudged), SeamRecipe.points(picked));
    List<Seam> seams = StitchFixture.seams(pieces, recipes);
    Stitch s = new Stitch(pieces, seams, StitchFixture.CHOICES, new double[] {Double.NaN, 31.5});
    assertFalse(s.anyFailed(), () -> s.seams.toString());
    project = StitchFixture.capture(s, recipes, new StitchResume.Lineage(null, null));
    document = StitchProjectIO.write(project);
  }

  private static String text() {
    return new String(document, StandardCharsets.UTF_8);
  }

  private static StitchProjectException refused(String changed) {
    return assertThrows(
        StitchProjectException.class,
        () -> StitchProjectIO.read(changed.getBytes(StandardCharsets.UTF_8)));
  }

  private static StitchProject with(List<StitchProject.Source> sources, StitchProject.Output out) {
    return new StitchProject(
        project.projectId(),
        project.versionId(),
        project.parentVersionId(),
        project.savedAt(),
        project.algorithm(),
        project.studyInstanceUid(),
        sources,
        project.choices(),
        project.seams(),
        project.blending(),
        out);
  }

  @Test
  void everythingComesBackAsItWasWritten() throws IOException {
    StitchProject read = StitchProjectIO.read(document);

    assertEquals(project, read);
    assertEquals(
        List.of("2.25.200", "2.25.201", "2.25.202"),
        read.sources().stream().map(StitchProject.Source::sopInstanceUid).toList(),
        "the pieces keep their order, head to foot");
    assertTrue(read.sources().stream().allMatch(s -> s.frame() == 1));
    assertEquals(0.35, read.sources().get(2).colMm(), "each piece keeps its own spacing");
    assertEquals(Spacing.Basis.PIXEL_SPACING, read.sources().get(2).basis());
    StitchProject.SeamEntry moved = read.seams().get(0);
    assertEquals(project.seams().get(0).decision().movedTo(), moved.decision().movedTo());
    assertEquals(moved.decision().movedTo(), moved.result().transform(), "where the user put it");
    assertEquals(Seam.Method.SEMI_AUTOMATIC, moved.result().method());
    assertTrue(Double.isNaN(moved.cutRequestedMm()), "an automatic cut has no requested height");
    StitchProject.SeamEntry manual = read.seams().get(1);
    assertEquals(4, manual.decision().points().size(), "the picked points");
    assertEquals(manual.decision().points(), manual.result().pairs());
    assertEquals(31.5, manual.cutRequestedMm());
    assertEquals("cut placed by hand", manual.cut().note());
    assertEquals(StitchProject.Blending.current(), read.blending());
    assertEquals(StitchFixture.CHOICES, read.choices());
    assertEquals(StitchWriter.SOFTWARE, read.algorithm());
    assertNull(read.parentVersionId());
  }

  @Test
  void theSameProjectAlwaysGivesTheSameBytes() throws IOException {
    assertArrayEquals(document, StitchProjectIO.write(project));
    assertArrayEquals(document, StitchProjectIO.write(StitchProjectIO.read(document)));
  }

  @Test
  void automaticMatchedPointsAreKept() throws IOException {
    StitchFixture f = new StitchFixture();
    List<Piece> pieces = f.pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch s = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);
    StitchProject p = StitchFixture.capture(s, recipes, StitchResume.Lineage.NEW);

    StitchProject read = StitchProjectIO.read(StitchProjectIO.write(p));

    assertFalse(read.seams().get(0).result().pairs().isEmpty(), "the points that drove the fit");
    assertEquals(s.seams, read.seams().stream().map(StitchProject.SeamEntry::result).toList());
    assertEquals(0.3, read.output().mmPerPixel(), 1e-9);
  }

  @Test
  void itHoldsNoPatientDetailsOrPaths() {
    String text = text();
    assertFalse(text.contains("Phantom"), "no patient name");
    assertFalse(text.contains(StitchFixture.PATIENT_ID), "no patient identifier");
    assertFalse(text.contains("/"), "no path");
    assertFalse(text.contains("\\\\"), "no path");
    assertFalse(text.toLowerCase().contains("file"), "no file name");
  }

  @Test
  void anUnknownSchemaVersionIsRefusedAsSuch() {
    StitchProjectException newer =
        refused(text().replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
    assertEquals(Kind.UNSUPPORTED_VERSION, newer.kind());
    assertTrue(newer.getMessage().contains("schema version 2"), newer.getMessage());
    assertEquals(
        Kind.UNSUPPORTED_VERSION,
        refused(text().replace("\"schemaVersion\":1", "\"schemaVersion\":0")).kind());
    // a later version may hold anything: it is refused for its version, not for its contents
    assertEquals(
        Kind.UNSUPPORTED_VERSION,
        refused("{\"format\":\"addisortho-stitch-project\",\"schemaVersion\":7,\"x\":1}").kind());
    assertEquals(
        Kind.MALFORMED,
        refused(text().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"")).kind());
  }

  @Test
  void whatIsNotAStitchProjectIsRefused() {
    for (String bad :
        List.of(
            "",
            "not json",
            "[]",
            "{}",
            "{\"format\":\"something-else\",\"schemaVersion\":1}",
            text().substring(0, text().length() / 2),
            text() + "{}",
            text().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"))) {
      assertEquals(Kind.MALFORMED, refused(bad).kind(), bad.length() > 60 ? "a long one" : bad);
    }
  }

  @Test
  void aMissingAnUnknownOrAWrongMemberIsRefusedAndNamed() {
    StitchProjectException missing = refused(text().replace("\"algorithm\":", "\"algorithn\":"));
    assertEquals(Kind.MALFORMED, missing.kind());
    assertTrue(missing.getMessage().contains("algorithm is missing"), missing.getMessage());

    StitchProjectException extra =
        refused(text().replace("\"blending\":{", "\"blending\":{\"warp\":true,"));
    assertEquals(Kind.MALFORMED, extra.kind());
    assertTrue(extra.getMessage().contains("blending.warp"), extra.getMessage());

    assertEquals(Kind.MALFORMED, refused(text().replace("\"frame\":1", "\"frame\":\"1\"")).kind());
    assertEquals(Kind.MALFORMED, refused(text().replace("\"frame\":1", "\"frame\":2")).kind());
    assertEquals(
        Kind.MALFORMED, refused(text().replace("\"thetaRad\":", "\"thetaRad\":NaN,\"x\":")).kind());
    assertEquals(
        Kind.MALFORMED,
        refused(text().replace("\"oneAcquisition\":true", "\"oneAcquisition\":false")).kind());
    assertEquals(
        Kind.MALFORMED,
        refused(text().replace("\"basis\":\"PIXEL_SPACING\"", "\"basis\":\"GUESS\"")).kind());
    assertEquals(
        Kind.MALFORMED,
        refused(text().replace("modality-values-float32be-v1", "file-bytes")).kind(),
        "a hash this viewer cannot check is not taken on trust");
  }

  @Test
  void aSeamStatusIsCheckOrFailedAndNothingElse() {
    assertTrue(text().contains("\"status\":\"check\""));
    for (String word : List.of("passed", "good", "verified", "CHECK")) {
      assertEquals(
          Kind.MALFORMED,
          refused(text().replace("\"status\":\"check\"", "\"status\":\"" + word + "\"")).kind(),
          word);
    }
  }

  @Test
  void aChangedValueNoLongerFitsTheChecksum() {
    double ty = project.seams().get(1).result().transform().ty();
    String moved = text().replace(Double.toString(ty), Double.toString(ty + 1));
    assertFalse(moved.equals(text()));
    assertEquals(Kind.DAMAGED, refused(moved).kind());
    assertEquals(
        Kind.DAMAGED, refused(text().replace("\"leg\":\"LEFT\"", "\"leg\":\"RIGHT\"")).kind());
  }

  @Test
  void theSaveRulesHoldInTheRecordItself() {
    StitchProject.Output out = project.output();
    // an ordinary draft never has a failed seam, an unverified one always has
    StitchProject.Output unverified =
        new StitchProject.Output(
            out.sopInstanceUid(),
            out.seriesInstanceUid(),
            true,
            Double.NaN,
            out.columns(),
            out.rows());
    assertRefused(with(project.sources(), unverified), "no seam failed");
    // an unverified draft or a stitch without one spacing basis never carries a pixel size
    StitchProject.Source first = project.sources().get(0);
    StitchProject.Source detector =
        new StitchProject.Source(
            first.sopInstanceUid(),
            first.sopClassUid(),
            first.seriesInstanceUid(),
            1,
            first.columns(),
            first.rows(),
            first.inverse(),
            first.colMm(),
            first.rowMm(),
            Spacing.Basis.DETECTOR,
            first.pixelHash());
    List<StitchProject.Source> mixedBasis = new ArrayList<>(project.sources());
    mixedBasis.set(0, detector);
    assertRefused(with(mixedBasis, out), "no scale");
    StitchProject.Output noSize =
        new StitchProject.Output(
            out.sopInstanceUid(),
            out.seriesInstanceUid(),
            false,
            Double.NaN,
            out.columns(),
            out.rows());
    assertRefused(with(project.sources(), noSize), "pixel size is missing");
    // never scaled pieces with unscaled ones
    StitchProject.Source unscaled =
        new StitchProject.Source(
            first.sopInstanceUid(),
            first.sopClassUid(),
            first.seriesInstanceUid(),
            1,
            first.columns(),
            first.rows(),
            first.inverse(),
            Spacing.UNSCALED_MM,
            Spacing.UNSCALED_MM,
            Spacing.Basis.NONE,
            first.pixelHash());
    List<StitchProject.Source> mixed = new ArrayList<>(project.sources());
    mixed.set(0, unscaled);
    assertRefused(with(mixed, noSize), "with and without pixel spacing");
    // two to four pieces, each once
    assertRefused(with(project.sources().subList(0, 1), out), "two to four pieces");
    List<StitchProject.Source> twice = new ArrayList<>(project.sources());
    twice.set(2, twice.get(0));
    assertRefused(with(twice, out), "used twice");
  }

  private static void assertRefused(StitchProject p, String because) {
    StitchProjectException e =
        assertThrows(StitchProjectException.class, () -> StitchProjectIO.write(p));
    assertEquals(Kind.MALFORMED, e.kind());
    assertNotNull(e.getMessage());
    assertTrue(e.getMessage().contains(because), e.getMessage());
  }

  @Test
  void aFailedSeamIsOnlyEverSavedAsAnUnverifiedDraft() throws IOException {
    StitchFixture f = new StitchFixture();
    List<Piece> pieces = f.pieces();
    List<SeamRecipe> recipes =
        List.of(
            SeamRecipe.match(SeamMatcher.Band.WHOLE),
            SeamRecipe.points(List.of(new Rigid.Pair(50, 10, 52, 260))));
    List<Seam> seams = StitchFixture.seams(pieces, recipes);
    assertEquals(Seam.Status.FAILED, seams.get(1).status(), "one picked pair confirms nothing");

    Stitch ordinary = new Stitch(pieces, seams, StitchFixture.CHOICES);
    assertRefused(
        StitchFixture.capture(ordinary, recipes, StitchResume.Lineage.NEW), "a seam failed");

    Stitch unverified = new Stitch(pieces, seams, StitchFixture.CHOICES, null, true);
    StitchProject saved =
        StitchProjectIO.read(
            StitchProjectIO.write(
                StitchFixture.capture(unverified, recipes, StitchResume.Lineage.NEW)));
    assertTrue(saved.output().unverified());
    assertTrue(Double.isNaN(saved.output().mmPerPixel()), "no scale on an unverified draft");
    assertEquals(Seam.Status.FAILED, saved.seams().get(1).result().status());
  }
}
