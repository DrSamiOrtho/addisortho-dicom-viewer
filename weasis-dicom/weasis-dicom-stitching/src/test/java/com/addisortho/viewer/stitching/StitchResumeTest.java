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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.stitching.StitchProjectException.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StitchResumeTest {

  private static final Compositor.Progress GO = (done, total) -> true;

  private static StitchFixture fixture;
  private static Stitch stitch;
  private static List<SeamRecipe> recipes;
  private static StitchProject saved;

  @BeforeAll
  static void build() throws IOException {
    fixture = new StitchFixture();
    List<Piece> pieces = fixture.pieces();
    recipes = StitchFixture.lineUp(2);
    stitch =
        new Stitch(
            pieces,
            StitchFixture.seams(pieces, recipes),
            StitchFixture.CHOICES,
            new double[] {Double.NaN, 31.5});
    assertFalse(stitch.anyFailed(), () -> stitch.seams.toString());
    // through the bytes, as after a restart
    saved =
        StitchProjectIO.read(
            StitchProjectIO.write(
                StitchFixture.capture(stitch, recipes, StitchResume.Lineage.NEW)));
  }

  private static StitchResume.Resumed reopen(StitchProject p, List<Piece> open) throws IOException {
    return StitchResume.recheck(p, StitchResume.sources(p, open), GO);
  }

  private static StitchProjectException refused(StitchProject p, List<Piece> open) {
    return assertThrows(StitchProjectException.class, () -> reopen(p, open));
  }

  /** {@code saved} with seam {@code i} replaced: what a changed or damaged record would hold. */
  private static StitchProject withSeam(int i, SeamRecipe decision, Seam result) {
    List<StitchProject.SeamEntry> seams = new ArrayList<>(saved.seams());
    StitchProject.SeamEntry old = seams.get(i);
    seams.set(i, new StitchProject.SeamEntry(decision, old.cutRequestedMm(), result, old.cut()));
    boolean failed = seams.stream().anyMatch(s -> s.result().status() == Seam.Status.FAILED);
    StitchProject.Output out = saved.output();
    return new StitchProject(
        saved.projectId(),
        saved.versionId(),
        saved.parentVersionId(),
        saved.savedAt(),
        saved.algorithm(),
        saved.studyInstanceUid(),
        saved.sources(),
        saved.choices(),
        seams,
        saved.blending(),
        new StitchProject.Output(
            out.sopInstanceUid(),
            out.seriesInstanceUid(),
            failed,
            failed ? Double.NaN : out.mmPerPixel(),
            out.columns(),
            out.rows()));
  }

  private static Seam status(Seam s, Seam.Status status) {
    return new Seam(
        s.transform(),
        s.method(),
        status,
        s.rmsMm(),
        s.rotationSigmaDeg(),
        s.shiftSigmaMm(),
        s.scale(),
        s.uniqueness(),
        s.holdoutMm(),
        s.pairs(),
        s.reasons());
  }

  @Test
  void aReopenedProjectIsTheSameStitchReadyToEdit() throws IOException {
    // the study as opened again: new pieces, not read yet, in another order, with a stranger
    List<Piece> open = new ArrayList<>(fixture.pieces());
    open.add(0, open.remove(2));
    Attributes other = StitchFixture.header(7, 0.3);
    open.add(new Piece(other, 667, 1000, () -> new float[667 * 1000]));

    StitchResume.Resumed r = reopen(saved, open);

    assertEquals(
        List.of("2.25.200", "2.25.201", "2.25.202"),
        r.pieces().stream().map(Piece::sopInstanceUid).toList(),
        "the saved pieces in the saved order");
    assertNotSame(stitch.pieces.get(0), r.pieces().get(0));
    assertEquals(stitch.seams, r.seams(), "every seam made again comes out as it was saved");
    assertEquals(recipes, r.recipes());
    assertEquals(StitchFixture.CHOICES, r.choices());
    assertTrue(Double.isNaN(r.cuts()[0]));
    assertEquals(31.5, r.cuts()[1]);
    assertEquals(List.of(), r.notes());
    Stitch again = r.stitch();
    assertEquals(saved.output().mmPerPixel(), again.outputMm());
    assertEquals(saved.output().columns(), again.columns());
    assertEquals(saved.output().rows(), again.rows());
    for (int i = 0; i < 2; i++) {
      assertEquals(saved.seams().get(i).cut(), again.cut(i));
    }
    assertEquals(stitch.derivation(), again.derivation());
  }

  @Test
  void anEditIsCheckedAgainAndSavedAsAChildWhileTheParentStays() throws IOException {
    StitchResume.Resumed r = reopen(saved, fixture.pieces());
    MatchImage upper = r.pieces().get(0).match();
    MatchImage lower = r.pieces().get(1).match();

    // the user drags seam 1 two millimetres: its measures belong to the old position
    Rigid dragged = Rigid.shift(2, 0).after(r.seams().get(0).transform());
    SeamRecipe moved = r.recipes().get(0).moved(dragged);
    Seam afterDrag = moved.compute(upper, lower);
    assertEquals(dragged, afterDrag.transform());
    assertEquals(Seam.Method.SEMI_AUTOMATIC, afterDrag.method());
    assertTrue(Double.isNaN(afterDrag.rmsMm()), "no residual is carried over from before the drag");
    assertTrue(afterDrag.reasons().get(0).contains("no longer measured"));

    // then Refine automatically from here: the same gates as a new stitch measure it again
    SeamRecipe refine = SeamRecipe.refine(SeamMatcher.Band.WHOLE, dragged);
    Seam refined = refine.compute(upper, lower);
    assertEquals(Seam.Status.CHECK, refined.status(), () -> refined.reasons().toString());
    assertFalse(Double.isNaN(refined.rmsMm()));
    assertTrue(
        StitchResume.apart(refined.transform(), stitch.seams.get(0).transform(), 200, 300) < 0.5,
        "the refit finds the seam again");

    // and the cut of seam 1 is dragged
    List<Seam> seams = List.of(refined, r.seams().get(1));
    List<SeamRecipe> edited = List.of(refine, r.recipes().get(1));
    Stitch s =
        new Stitch(
            r.pieces(), seams, r.choices(), new double[] {r.stitch().cut(0).yMm() + 6, 31.5});
    StitchProject child =
        StitchFixture.capture(
            s, edited, new StitchResume.Lineage(saved.projectId(), saved.versionId()));

    assertEquals(saved.projectId(), child.projectId());
    assertEquals(saved.versionId(), child.parentVersionId());
    assertNotEquals(saved.versionId(), child.versionId());
    assertEquals(saved.sources(), child.sources(), "the same source images");
    assertEquals(refine, child.seams().get(0).decision());
    assertEquals("cut placed by hand", child.seams().get(0).cut().note());

    // both reopen, each as it was saved
    StitchResume.Resumed childAgain =
        reopen(StitchProjectIO.read(StitchProjectIO.write(child)), fixture.pieces());
    assertEquals(seams, childAgain.seams());
    assertEquals(List.of(), childAgain.notes());
    assertEquals(stitch.seams, reopen(saved, fixture.pieces()).seams(), "the parent is unchanged");
  }

  @Test
  void aSeamPutByHandReopensWhereTheUserPutIt() throws IOException {
    Rigid put = Rigid.shift(1.5, -0.7).after(stitch.seams.get(1).transform());
    List<SeamRecipe> decisions =
        List.of(recipes.get(0), SeamRecipe.match(SeamMatcher.Band.WHOLE).moved(put));
    Seam result = stitch.seams.get(1).adjusted(put);
    Stitch moved =
        new Stitch(stitch.pieces, List.of(stitch.seams.get(0), result), StitchFixture.CHOICES);
    StitchProject p =
        StitchProjectIO.read(
            StitchProjectIO.write(
                StitchFixture.capture(moved, decisions, StitchResume.Lineage.NEW)));

    StitchResume.Resumed r = reopen(p, fixture.pieces());

    assertEquals(put, r.seams().get(1).transform());
    assertEquals(Seam.Method.SEMI_AUTOMATIC, r.seams().get(1).method());
    assertEquals(result, r.seams().get(1));
    assertEquals(List.of(), r.notes());
  }

  @Test
  void aCutThatIsNoLongerFoundWhereItWasSavedIsTold() throws IOException {
    List<StitchProject.SeamEntry> seams = new ArrayList<>(saved.seams());
    StitchProject.SeamEntry first = seams.get(0);
    seams.set(
        0,
        new StitchProject.SeamEntry(
            first.decision(),
            first.cutRequestedMm(),
            first.result(),
            new Cut.Row(first.cut().yMm() - 9, first.cut().note(), first.cut().clean())));
    StitchProject p =
        new StitchProject(
            saved.projectId(),
            saved.versionId(),
            saved.parentVersionId(),
            saved.savedAt(),
            saved.algorithm(),
            saved.studyInstanceUid(),
            saved.sources(),
            saved.choices(),
            seams,
            saved.blending(),
            saved.output());

    StitchResume.Resumed r = reopen(p, fixture.pieces());

    assertEquals(1, r.notes().size());
    assertTrue(r.notes().get(0).startsWith("Seam 1: the cut is now at"), r.notes().toString());
    assertEquals(stitch.seams, r.seams(), "the seams themselves are as saved");
  }

  @Test
  void aSavedCheckThatTheGatesNoLongerGiveIsNotCarriedForward() throws IOException {
    // the record says "check", but its decision (two picked pairs) cannot give that
    Rigid t = stitch.seams.get(0).transform();
    double[] a = t.apply(40, 10);
    double[] b = t.apply(160, 20);
    SeamRecipe twoPairs =
        SeamRecipe.points(
            List.of(new Rigid.Pair(40, 10, a[0], a[1]), new Rigid.Pair(160, 20, b[0], b[1])));
    Seam honest = twoPairs.compute(null, null);
    assertEquals(Seam.Status.FAILED, honest.status());
    StitchProject claimed = withSeam(0, twoPairs, status(honest, Seam.Status.CHECK));
    assertFalse(claimed.anyFailed());

    StitchResume.Resumed r = reopen(claimed, fixture.pieces());

    assertEquals(Seam.Status.FAILED, r.seams().get(0).status());
    assertTrue(r.stitch().anyFailed(), "so the ordinary Save stays blocked");
    assertTrue(r.seams().get(0).reasons().get(0).contains("did not come out as it was saved"));
    assertEquals(1, r.notes().size());
    assertEquals(Seam.Status.CHECK, r.seams().get(1).status(), "the other seam is not touched");
  }

  @Test
  void aSeamSavedAsFailedIsNeverPassedByReopening() throws IOException {
    // saved as failed although its decision would give "check" today
    Seam check = stitch.seams.get(0);
    StitchProject claimed = withSeam(0, recipes.get(0), status(check, Seam.Status.FAILED));

    StitchResume.Resumed r = reopen(claimed, fixture.pieces());

    assertEquals(Seam.Status.FAILED, r.seams().get(0).status());
    assertEquals(check.transform(), r.seams().get(0).transform(), "held where it was saved");
    assertEquals(recipes.get(0), r.recipes().get(0));
    assertFalse(r.stitch().hasScale() && !r.stitch().anyFailed());
  }

  @Test
  void aSeamThatComesOutSomewhereElseIsHeldWhereItWasSavedAndFailed() throws IOException {
    Seam was = stitch.seams.get(1);
    Rigid elsewhere = Rigid.shift(0.4, 0).after(was.transform());
    Seam claimed =
        new Seam(
            elsewhere,
            was.method(),
            was.status(),
            was.rmsMm(),
            was.rotationSigmaDeg(),
            was.shiftSigmaMm(),
            was.scale(),
            was.uniqueness(),
            was.holdoutMm(),
            was.pairs(),
            was.reasons());

    StitchResume.Resumed r = reopen(withSeam(1, recipes.get(1), claimed), fixture.pieces());

    assertEquals(Seam.Status.FAILED, r.seams().get(1).status());
    assertEquals(elsewhere, r.seams().get(1).transform(), "no piece is moved without saying so");
    assertTrue(Double.isNaN(r.seams().get(1).rmsMm()), "and the old measures are dropped");
    assertTrue(r.notes().get(0).startsWith("Seam 2:"), r.notes().toString());
    // a twentieth of a millimetre is the same seam
    assertTrue(StitchResume.apart(was.transform(), was.transform(), 200, 300) <= 0);
    assertEquals(0.4, StitchResume.apart(was.transform(), elsewhere, 200, 300), 1e-9);
  }

  @Test
  void aMissingPieceRefusesTheWholeProject() {
    List<Piece> open = new ArrayList<>(fixture.pieces());
    open.remove(1);

    StitchProjectException e = refused(saved, open);

    assertEquals(Kind.SOURCES, e.kind());
    assertEquals(1, e.problems().size());
    assertTrue(e.problems().get(0).startsWith("piece 2 of 3 is not among"), e.getMessage());
    assertTrue(e.getMessage().contains("Nothing was put in their place"));
    assertFalse(e.getMessage().contains("Phantom"));
  }

  @Test
  void aStitchIsNeverReopenedOnSomeOfItsPieces() {
    StitchProjectException e = refused(saved, fixture.pieces().subList(0, 1));

    assertEquals(Kind.SOURCES, e.kind());
    assertEquals(2, e.problems().size(), "each missing piece is named");
    assertEquals(Kind.SOURCES, refused(saved, List.of()).kind());
  }

  @Test
  void aPieceWhosePixelsChangedIsRefused() {
    List<Piece> open = new ArrayList<>(fixture.pieces());
    open.set(2, fixture.pieceWithOnePixelChanged(2));

    StitchProjectException e = refused(saved, open);

    assertEquals(Kind.SOURCES, e.kind());
    assertEquals(
        List.of("piece 3 of 3 has changed since the stitch was saved: its pixels differ"),
        e.problems());
  }

  @Test
  void anotherImageIsNeverTakenInAMissingOnesPlace() {
    // the same pixels under another identity, as a re-export would be
    List<Piece> open = new ArrayList<>(fixture.pieces());
    Piece twin = fixture.piece(1);
    twin.header.setString(Tag.SOPInstanceUID, VR.UI, "2.25.999");
    open.set(1, twin);

    assertEquals(Kind.SOURCES, refused(saved, open).kind());

    // and the right identity twice cannot be told apart
    List<Piece> doubled = new ArrayList<>(fixture.pieces());
    doubled.add(fixture.piece(0));
    StitchProjectException twice = refused(saved, doubled);
    assertTrue(twice.problems().get(0).contains("more than once"), twice.getMessage());
  }

  @Test
  void aPieceWhoseHeaderChangedIsRefused() {
    List<Piece> spacing = new ArrayList<>(fixture.pieces());
    Attributes h = new Attributes(spacing.get(0).header);
    h.setString(Tag.PixelSpacing, VR.DS, "0.31", "0.31");
    spacing.set(0, new Piece(h, spacing.get(0).width, spacing.get(0).height, () -> null));
    assertTrue(refused(saved, spacing).problems().get(0).contains("pixel spacing differs"));

    // an image that is no longer acceptable as a piece stays refused when reopening
    List<Piece> stitched = new ArrayList<>(fixture.pieces());
    stitched.get(1).header.setString(Tag.ImageType, VR.CS, "DERIVED", "PRIMARY", "STITCHED");
    assertTrue(refused(saved, stitched).problems().get(0).contains("can no longer be a piece"));

    List<Piece> ct = new ArrayList<>(fixture.pieces());
    ct.get(2).header.setString(Tag.Modality, VR.CS, "CT");
    assertTrue(refused(saved, ct).problems().get(0).contains("not a plain X-ray"));
  }

  @Test
  void aProjectFromAnotherAlgorithmIsCheckedAgainAndSaysSo() throws IOException {
    StitchProject older =
        new StitchProject(
            saved.projectId(),
            saved.versionId(),
            null,
            saved.savedAt(),
            "Addis Ortho Viewer long-leg stitch 0.4 (draft)",
            saved.studyInstanceUid(),
            saved.sources(),
            saved.choices(),
            saved.seams(),
            saved.blending(),
            saved.output());

    StitchResume.Resumed r = reopen(older, fixture.pieces());

    assertEquals(1, r.notes().size());
    assertTrue(r.notes().get(0).contains("0.4"), r.notes().toString());
    assertEquals(stitch.seams, r.seams());
  }

  @Test
  void reopeningCanBeCancelled() throws IOException {
    List<Piece> found = StitchResume.sources(saved, fixture.pieces());
    assertThrows(
        CancellationException.class, () -> StitchResume.recheck(saved, found, (d, t) -> d < 2));
  }

  @Test
  void theHashFollowsThePixelsTheStitchUses() throws IOException {
    Piece a = fixture.piece(0);
    assertEquals(null, a.pixelHash(), "not known before the piece is read");
    a.prepare();
    Piece b = fixture.piece(0);
    b.prepare();
    assertEquals(a.pixelHash(), b.pixelHash());
    assertEquals(saved.sources().get(0).pixelHash(), a.pixelHash());
    Piece changed = fixture.pieceWithOnePixelChanged(0);
    changed.prepare();
    assertNotEquals(a.pixelHash(), changed.pixelHash());

    float[] v = {1, 2, 3, 4, 5, 6};
    assertNotEquals(Piece.hash(v, 3, 2), Piece.hash(v, 2, 3), "the shape is part of it");
    assertArrayEquals(new float[] {1, 2, 3, 4, 5, 6}, v);
    assertEquals(64, Piece.hash(v, 3, 2).length());
  }
}
