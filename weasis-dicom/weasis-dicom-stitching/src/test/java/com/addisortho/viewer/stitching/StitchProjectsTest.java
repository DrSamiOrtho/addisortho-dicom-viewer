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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.addisortho.viewer.stitching.StitchProjectException.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StitchProjectsTest {

  private static Stitch stitch;
  private static List<SeamRecipe> recipes;

  @TempDir Path dir;

  @BeforeAll
  static void build() throws IOException {
    List<Piece> pieces = new StitchFixture().pieces();
    recipes = StitchFixture.lineUp(2);
    stitch = new Stitch(pieces, StitchFixture.seams(pieces, recipes), StitchFixture.CHOICES);
  }

  private FolderProjectStore store() {
    return new FolderProjectStore(dir);
  }

  private static StitchProject version(String projectId, String parent, int minute) {
    return StitchResume.capture(
        stitch,
        recipes,
        new StitchResume.Lineage(projectId, parent),
        Instant.parse("2026-11-10T06:%02d:00Z".formatted(minute)),
        "2.25.9" + minute,
        "2.25.8" + minute);
  }

  private static Kind refusal(StitchProjects projects, StitchProject version) {
    return assertThrows(StitchProjectException.class, () -> projects.save(version)).kind();
  }

  @Test
  void aSavedVersionLoadsAsItWasSaved() throws IOException {
    StitchProjects projects = new StitchProjects(store());
    StitchProject first = version(null, null, 1);

    projects.save(first);

    assertEquals(first, projects.load(StitchFixture.STUDY, first.versionId()));
    assertEquals(
        Kind.NOT_FOUND,
        assertThrows(
                StitchProjectException.class,
                () -> projects.load(StitchFixture.STUDY, UUID.randomUUID().toString()))
            .kind());
  }

  @Test
  void eachEditIsANewVersionUnderItsParentAndTheEarlierOnesStay() throws IOException {
    FolderProjectStore store = store();
    StitchProjects projects = new StitchProjects(store);
    StitchProject first = version(null, null, 1);
    projects.save(first);
    byte[] firstBytes = Files.readAllBytes(store.file(StitchFixture.STUDY, first.versionId()));

    StitchProject second = version(first.projectId(), first.versionId(), 2);
    projects.save(second);
    StitchProject third = version(first.projectId(), second.versionId(), 3);
    projects.save(third);
    // a second edit of the first version: the lineage is a tree, not a line
    StitchProject branch = version(first.projectId(), first.versionId(), 4);
    projects.save(branch);

    List<StitchProjects.Listed> listed = projects.list(StitchFixture.STUDY);
    assertEquals(
        List.of(first.versionId(), second.versionId(), third.versionId(), branch.versionId()),
        listed.stream().map(StitchProjects.Listed::versionId).toList(),
        "oldest first");
    assertNull(listed.get(0).project().parentVersionId());
    assertEquals(first.versionId(), listed.get(1).project().parentVersionId());
    assertEquals(second.versionId(), listed.get(2).project().parentVersionId());
    assertEquals(first.versionId(), listed.get(3).project().parentVersionId());
    assertTrue(listed.stream().allMatch(l -> l.project().projectId().equals(first.projectId())));
    assertArrayEquals(
        firstBytes,
        Files.readAllBytes(store.file(StitchFixture.STUDY, first.versionId())),
        "the first version is untouched by the later ones");
    assertEquals(first, projects.load(StitchFixture.STUDY, first.versionId()));
    // every version still names the same sources
    for (StitchProjects.Listed l : listed) {
      assertEquals(first.sources(), l.project().sources());
    }
  }

  @Test
  void aVersionIsNeverListedBeforeTheOneItWasEditedFrom() throws IOException {
    StitchProjects projects = new StitchProjects(store());
    // a chain saved within one instant, and its last link dated before all the others
    List<String> chain = new ArrayList<>();
    String projectId = null;
    String parent = null;
    for (int i = 0; i < 6; i++) {
      StitchProject v = version(projectId, parent, i == 5 ? 0 : 7);
      projects.save(v);
      chain.add(v.versionId());
      projectId = v.projectId();
      parent = v.versionId();
    }

    assertEquals(
        chain,
        projects.list(StitchFixture.STUDY).stream().map(StitchProjects.Listed::versionId).toList());
  }

  @Test
  void aSavedVersionIsNeverWrittenOver() throws IOException {
    FolderProjectStore store = store();
    StitchProjects projects = new StitchProjects(store);
    StitchProject first = version(null, null, 1);
    projects.save(first);
    StitchProject second = version(first.projectId(), first.versionId(), 2);
    projects.save(second);
    byte[] before = Files.readAllBytes(store.file(StitchFixture.STUDY, second.versionId()));

    // the same identifier with other contents
    StitchProject impostor =
        new StitchProject(
            second.projectId(),
            second.versionId(),
            second.parentVersionId(),
            second.savedAt().plusSeconds(60),
            second.algorithm(),
            second.studyInstanceUid(),
            second.sources(),
            second.choices(),
            second.seams(),
            second.blending(),
            second.output());
    assertEquals(Kind.EXISTS, refusal(projects, impostor));
    assertThrows(
        FileAlreadyExistsException.class,
        () -> store.put(StitchFixture.STUDY, second.versionId(), new byte[] {1}));

    assertArrayEquals(
        before, Files.readAllBytes(store.file(StitchFixture.STUDY, second.versionId())));
    assertEquals(2, projects.list(StitchFixture.STUDY).size());
  }

  @Test
  void anEditNeedsItsParentInTheSameProjectAndStudy() throws IOException {
    StitchProjects projects = new StitchProjects(store());
    StitchProject first = version(null, null, 1);
    projects.save(first);
    StitchProject other = version(null, null, 2);
    projects.save(other);
    assertNotEquals(first.projectId(), other.projectId());

    assertEquals(
        Kind.LINEAGE,
        refusal(projects, version(first.projectId(), UUID.randomUUID().toString(), 3)),
        "a parent that is not saved");
    assertEquals(
        Kind.LINEAGE,
        refusal(projects, version(first.projectId(), other.versionId(), 3)),
        "a parent of another project");
    assertEquals(
        Kind.LINEAGE,
        refusal(projects, version(first.projectId(), null, 3)),
        "a second first version of the same project");
    assertEquals(2, projects.list(StitchFixture.STUDY).size(), "nothing refused was kept");
  }

  @Test
  void aVersionThatCannotBeReadIsListedWithItsReasonNotHidden() throws IOException {
    FolderProjectStore store = store();
    StitchProjects projects = new StitchProjects(store);
    StitchProject first = version(null, null, 1);
    projects.save(first);
    String torn = UUID.randomUUID().toString();
    store.put(StitchFixture.STUDY, torn, "{\"format\":".getBytes(StandardCharsets.UTF_8));
    String newer = UUID.randomUUID().toString();
    store.put(
        StitchFixture.STUDY,
        newer,
        "{\"format\":\"addisortho-stitch-project\",\"schemaVersion\":9}"
            .getBytes(StandardCharsets.UTF_8));

    List<StitchProjects.Listed> listed = projects.list(StitchFixture.STUDY);

    assertEquals(3, listed.size());
    assertNotNull(listed.get(0).project());
    assertEquals(first.versionId(), listed.get(0).versionId());
    for (StitchProjects.Listed bad : listed.subList(1, 3)) {
      assertNull(bad.project());
      assertNotNull(bad.problem());
    }
    assertTrue(
        listed.stream().anyMatch(l -> l.problem() != null && l.problem().contains("version 9")));
    // an edit cannot hang from a parent that cannot be read
    assertEquals(Kind.LINEAGE, refusal(projects, version(first.projectId(), torn, 5)));
  }

  @Test
  void aVersionKeptUnderAnotherStudyOrNameIsRefused() throws IOException {
    FolderProjectStore store = store();
    StitchProjects projects = new StitchProjects(store);
    StitchProject first = version(null, null, 1);
    byte[] document = StitchProjectIO.write(first);
    String otherId = UUID.randomUUID().toString();
    store.put(StitchFixture.STUDY, otherId, document);
    store.put("2.25.777", first.versionId(), document);

    assertEquals(
        Kind.MALFORMED,
        assertThrows(
                StitchProjectException.class, () -> projects.load(StitchFixture.STUDY, otherId))
            .kind());
    assertEquals(
        Kind.MALFORMED,
        assertThrows(
                StitchProjectException.class, () -> projects.load("2.25.777", first.versionId()))
            .kind());
  }

  @Test
  void theFolderStoreListsOnlyWholeVersionsOfTheStudyAsked() throws IOException {
    FolderProjectStore store = store();
    assertEquals(List.of(), store.list(StitchFixture.STUDY));
    String id = UUID.randomUUID().toString();
    store.put(StitchFixture.STUDY, id, new byte[] {1, 2, 3});
    // what an interrupted put would leave behind
    Files.write(dir.resolve(StitchFixture.STUDY).resolve("." + id + ".part"), new byte[] {9});

    assertEquals(List.of(id), store.list(StitchFixture.STUDY));
    assertArrayEquals(new byte[] {1, 2, 3}, store.get(StitchFixture.STUDY, id));
    assertEquals(List.of(), store.list("2.25.555"));
    assertThrows(NoSuchFileException.class, () -> store.get("2.25.555", id));
  }
}
