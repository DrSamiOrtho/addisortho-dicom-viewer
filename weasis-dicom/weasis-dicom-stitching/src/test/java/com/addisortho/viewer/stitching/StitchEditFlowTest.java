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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.library.StudyStore;

/**
 * Save, reopen, edit and save again, through the study store's own save and a project store in a
 * temporary folder: what the stitch window does, without the window.
 */
class StitchEditFlowTest {

  private static final Compositor.Progress GO = (done, total) -> true;

  @TempDir Path dir;

  /**
   * A project store over this test's folder; each call is a new object, as after a restart. {@link
   * LibraryStitchEditFlowTest} runs every test here against the study library's own store.
   */
  ProjectStore openStore() {
    return new FolderProjectStore(dir.resolve("projects"));
  }

  /** The bytes kept on the disk for a version, read past the store. */
  byte[] keptBytes(String study, String versionId) throws IOException {
    return Files.readAllBytes(
        new FolderProjectStore(dir.resolve("projects")).file(study, versionId));
  }

  private static Attributes read(Path file) throws IOException {
    try (DicomInputStream in = new DicomInputStream(file.toFile())) {
      return in.readDataset();
    }
  }

  /** What every saved stitch must say of itself, edited or not. */
  private static void assertDraftSecondaryCapture(Attributes a) {
    assertEquals(UID.SecondaryCaptureImageStorage, a.getString(Tag.SOPClassUID));
    assertArrayEquals(new String[] {"DERIVED", "SECONDARY"}, a.getStrings(Tag.ImageType));
    assertEquals(3, a.getSequence(Tag.SourceImageSequence).size());
    for (Attributes item : a.getSequence(Tag.SourceImageSequence)) {
      assertEquals("NO", item.getString(Tag.SpatialLocationsPreserved));
    }
    assertEquals(
        List.of("2.25.200", "2.25.201", "2.25.202"),
        a.getSequence(Tag.SourceImageSequence).stream()
            .map(i -> i.getString(Tag.ReferencedSOPInstanceUID))
            .toList());
    assertEquals("113090", a.getNestedDataset(Tag.DerivationCodeSequence).getString(Tag.CodeValue));
    assertEquals("YES", a.getString(Tag.BurnedInAnnotation), "a piece said YES");
    assertTrue(a.getString(Tag.ImageComments).contains("Stitched draft"));
    assertTrue(a.getString(Tag.ImageComments).contains("unverified"));
  }

  @Test
  void saveReopenEditAndSaveAgainKeepsBothVersions() throws IOException {
    StudyStore library = new StudyStore(dir.resolve("library"), true);
    StitchProjects projects = new StitchProjects(openStore());
    StitchFixture fixture = new StitchFixture();

    // a new stitch, lined up and saved
    List<Piece> pieces = fixture.pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch first = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);
    StitchService.Saved one =
        StitchService.save(library, projects, first, recipes, StitchResume.Lineage.NEW, GO);
    assertNull(one.versionProblem());
    assertNull(one.version().parentVersionId());
    assertEquals(one.picture().instance().sopUid(), one.version().output().sopInstanceUid());
    Attributes firstPicture = read(one.picture().instance().file());
    assertDraftSecondaryCapture(firstPicture);
    assertEquals(0.3, firstPicture.getDouble(Tag.PixelSpacing, 0), 1e-9);
    byte[] firstVersion = keptBytes(StitchFixture.STUDY, one.version().versionId());
    byte[] firstPixels = Files.readAllBytes(one.picture().instance().file());

    // the viewer is closed and started again: only the stores remain
    StitchProjects later = new StitchProjects(openStore());
    List<StitchProjects.Listed> listed = later.list(StitchFixture.STUDY);
    assertEquals(1, listed.size());
    StitchProject reopened = listed.get(0).project();
    StitchResume.Resumed r =
        StitchResume.recheck(reopened, StitchResume.sources(reopened, fixture.pieces()), GO);
    assertEquals(first.seams, r.seams());

    // seam 2 is dragged and refined again, then saved
    Rigid dragged = Rigid.shift(-1.5, 1).after(r.seams().get(1).transform());
    SeamRecipe refine = SeamRecipe.refine(SeamMatcher.Band.WHOLE, dragged);
    Seam refined = refine.compute(r.pieces().get(1).match(), r.pieces().get(2).match());
    assertEquals(Seam.Status.CHECK, refined.status(), () -> refined.reasons().toString());
    Stitch edited =
        new Stitch(r.pieces(), List.of(r.seams().get(0), refined), r.choices(), r.cuts());
    StitchService.Saved two =
        StitchService.save(
            library,
            later,
            edited,
            List.of(r.recipes().get(0), refine),
            new StitchResume.Lineage(reopened.projectId(), reopened.versionId()),
            GO);

    assertNull(two.versionProblem());
    assertEquals(one.version().versionId(), two.version().parentVersionId());
    assertEquals(one.version().projectId(), two.version().projectId());
    assertEquals(one.version().sources(), two.version().sources());
    Attributes secondPicture = read(two.picture().instance().file());
    assertDraftSecondaryCapture(secondPicture);
    assertEquals(
        "Long leg stitched (draft, semi-automatic, not reviewed)",
        secondPicture.getString(Tag.SeriesDescription));
    assertNotEquals(one.picture().instance().file(), two.picture().instance().file());
    assertNotEquals(
        firstPicture.getString(Tag.SOPInstanceUID), secondPicture.getString(Tag.SOPInstanceUID));

    // the first version and its picture are exactly as they were, and both versions reopen
    assertArrayEquals(firstVersion, keptBytes(StitchFixture.STUDY, one.version().versionId()));
    assertArrayEquals(firstPixels, Files.readAllBytes(one.picture().instance().file()));
    assertEquals(2, later.list(StitchFixture.STUDY).size());
    for (StitchProjects.Listed l : later.list(StitchFixture.STUDY)) {
      StitchResume.Resumed again =
          StitchResume.recheck(
              l.project(), StitchResume.sources(l.project(), fixture.pieces()), GO);
      assertEquals(List.of(), again.notes());
      assertFalse(again.stitch().anyFailed());
    }
  }

  @Test
  void aReopenedSeamThatFailsCanOnlyBeSavedUnverifiedAndWithoutScale() throws IOException {
    StudyStore library = new StudyStore(dir.resolve("library"), true);
    StitchProjects projects = new StitchProjects(openStore());
    StitchFixture fixture = new StitchFixture();
    List<Piece> pieces = fixture.pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch first = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);
    StitchProject parent =
        StitchService.save(library, projects, first, recipes, StitchResume.Lineage.NEW, GO)
            .version();

    // reopened, seam 1 is replaced by one picked pair: unconfirmed, so failed
    StitchResume.Resumed r =
        StitchResume.recheck(parent, StitchResume.sources(parent, fixture.pieces()), GO);
    double[] to = r.seams().get(0).transform().apply(60, 15);
    SeamRecipe onePair = SeamRecipe.points(List.of(new Rigid.Pair(60, 15, to[0], to[1])));
    List<Seam> seams = List.of(onePair.compute(null, null), r.seams().get(1));
    List<SeamRecipe> edited = List.of(onePair, r.recipes().get(1));
    StitchResume.Lineage child = new StitchResume.Lineage(parent.projectId(), parent.versionId());

    // an ordinary save of a failed seam is refused before anything is written, whether or not
    // versions are kept: no picture, no version
    Stitch ordinary = new Stitch(r.pieces(), seams, r.choices(), r.cuts());
    assertTrue(ordinary.anyFailed());
    long before = pictures();
    for (StitchProjects kept : new StitchProjects[] {projects, null}) {
      IOException refused =
          assertThrows(
              IOException.class,
              () -> StitchService.save(library, kept, ordinary, edited, child, GO));
      assertTrue(refused.getMessage().contains("seam 1 failed"), refused.getMessage());
    }
    assertEquals(before, pictures(), "no picture was written");
    assertEquals(1, projects.list(StitchFixture.STUDY).size(), "and no version");

    Stitch unverified = new Stitch(r.pieces(), seams, r.choices(), r.cuts(), true);
    StitchService.Saved saved =
        StitchService.save(library, projects, unverified, edited, child, GO);
    assertNull(saved.versionProblem());
    assertTrue(saved.version().output().unverified());
    Attributes picture = read(saved.picture().instance().file());
    assertNull(picture.getString(Tag.PixelSpacing), "nothing can be measured on it");
    assertTrue(picture.getString(Tag.SeriesDescription).contains("UNVERIFIED"));
    assertTrue(picture.getString(Tag.ImageComments).startsWith("UNVERIFIED STITCH: seam 1 failed"));
    assertEquals("YES", picture.getString(Tag.BurnedInAnnotation));
    assertEquals(3, picture.getSequence(Tag.SourceImageSequence).size());

    // reopened once more it is still failed, and the parent still has its scale
    StitchResume.Resumed again =
        StitchResume.recheck(
            saved.version(), StitchResume.sources(saved.version(), fixture.pieces()), GO);
    assertEquals(Seam.Status.FAILED, again.seams().get(0).status());
    assertEquals(seams, again.seams());
    assertFalse(Double.isNaN(parent.output().mmPerPixel()));
  }

  /** The files in the study library, its own hidden folders aside. */
  private long pictures() throws IOException {
    Path library = dir.resolve("library");
    if (!Files.isDirectory(library)) {
      return 0;
    }
    try (var files = Files.walk(library)) {
      return files
          .filter(Files::isRegularFile)
          .filter(f -> !library.relativize(f).toString().startsWith("."))
          .count();
    }
  }

  @Test
  void aVersionThatCouldNotBeKeptIsRefusedBeforeThePictureIsWritten() throws IOException {
    StudyStore library = new StudyStore(dir.resolve("library"), true);
    StitchProjects projects = new StitchProjects(openStore());
    List<Piece> pieces = new StitchFixture().pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch s = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);

    // an edit of a version that is not saved
    StitchResume.Lineage orphan =
        new StitchResume.Lineage(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    StitchProjectException lineage =
        assertThrows(
            StitchProjectException.class,
            () -> StitchService.save(library, projects, s, recipes, orphan, GO));
    assertEquals(StitchProjectException.Kind.LINEAGE, lineage.kind());

    // a stitch the project format cannot hold: the same image twice
    List<Piece> twice = List.of(pieces.get(0), pieces.get(1), pieces.get(0));
    Stitch impossible = new Stitch(twice, s.seams, StitchFixture.CHOICES);
    StitchProjectException format =
        assertThrows(
            StitchProjectException.class,
            () ->
                StitchService.save(
                    library, projects, impossible, recipes, StitchResume.Lineage.NEW, GO));
    assertEquals(StitchProjectException.Kind.MALFORMED, format.kind());

    assertEquals(0, pictures(), "neither left a picture");
    assertEquals(List.of(), projects.list(StitchFixture.STUDY));
  }

  @Test
  void whenTheProjectStoreFailsAfterThePictureThePictureStandsAndTheReasonIsGiven()
      throws IOException {
    StudyStore library = new StudyStore(dir.resolve("library"), true);
    ProjectStore full =
        new ProjectStore() {
          @Override
          public void put(String study, String version, byte[] document) throws IOException {
            throw new IOException("the disk is full");
          }

          @Override
          public byte[] get(String study, String version) throws IOException {
            throw new NoSuchFileException(version);
          }

          @Override
          public List<String> list(String study) {
            return List.of();
          }
        };
    List<Piece> pieces = new StitchFixture().pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch s = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);

    StitchService.Saved saved =
        StitchService.save(
            library, new StitchProjects(full), s, recipes, StitchResume.Lineage.NEW, GO);

    assertNull(saved.version());
    assertEquals("the disk is full", saved.versionProblem());
    assertTrue(Files.isRegularFile(saved.picture().instance().file()));
    assertEquals(1, pictures());
  }

  @Test
  void withoutAProjectStoreTheSaveIsThePictureAloneAsBefore() throws IOException {
    StudyStore library = new StudyStore(dir.resolve("library"), true);
    List<Piece> pieces = new StitchFixture().pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch s = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);

    StitchService.Saved saved =
        StitchService.save(library, null, s, recipes, StitchResume.Lineage.NEW, GO);

    assertNull(saved.version());
    assertNull(saved.versionProblem());
    assertTrue(Files.isRegularFile(saved.picture().instance().file()));
    assertTrue(
        StitchService.projectStore().orElseThrow() instanceof LibraryProjectStore,
        "the viewer keeps its projects in the study library");
  }
}
