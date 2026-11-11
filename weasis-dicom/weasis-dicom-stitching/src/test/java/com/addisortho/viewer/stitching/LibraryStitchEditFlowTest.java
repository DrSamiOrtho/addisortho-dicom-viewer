/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.explorer.library.StudyStore;

/**
 * Every save, restart and reopen of {@link StitchEditFlowTest}, with the versions kept by the study
 * library beside the pictures, as the viewer keeps them.
 */
class LibraryStitchEditFlowTest extends StitchEditFlowTest {

  @Override
  ProjectStore openStore() {
    return new LibraryProjectStore(() -> new StudyStore(dir.resolve("library"), true));
  }

  @Override
  byte[] keptBytes(String study, String versionId) throws IOException {
    Path file =
        dir.resolve("library")
            .resolve(".projects")
            .resolve(LibraryProjectStore.KIND)
            .resolve(study)
            .resolve(versionId + ".json");
    return Files.readAllBytes(file);
  }

  @Test
  void whenTheLibraryCannotKeepTheVersionThePictureStandsAndTheUserIsToldWhy() throws IOException {
    StudyStore library = new StudyStore(dir.resolve("library"), true);
    Files.createDirectories(dir.resolve("library"));
    Files.writeString(dir.resolve("library").resolve(".projects"), "a file where the folder goes");
    List<Piece> pieces = new StitchFixture().pieces();
    List<SeamRecipe> recipes = StitchFixture.lineUp(2);
    Stitch s = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);

    StitchService.Saved saved =
        StitchService.save(
            library,
            new StitchProjects(openStore()),
            s,
            recipes,
            StitchResume.Lineage.NEW,
            (done, total) -> true);

    assertNull(saved.version());
    assertTrue(Files.isRegularFile(saved.picture().instance().file()));
    assertTrue(
        saved.versionProblem().startsWith("It could not be written to the study library"),
        saved.versionProblem());
    assertFalse(saved.versionProblem().contains(dir.toString()), "no path for the user");
    assertEquals(1, library.listStudies().size());
  }
}
