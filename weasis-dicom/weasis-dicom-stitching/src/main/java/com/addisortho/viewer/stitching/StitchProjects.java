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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The versions of stitch projects in a {@link ProjectStore}: saving a new version under its parent,
 * loading one, and listing a study's. Every version is checked as it is read; an edit is always a
 * new version and the earlier ones stay as they were.
 */
final class StitchProjects {

  /**
   * One version of a study as listed: the project, or why it cannot be read. A version that cannot
   * be read is still listed, so it is never mistaken for one that does not exist.
   */
  record Listed(String versionId, StitchProject project, String problem) {}

  private final ProjectStore store;

  StitchProjects(ProjectStore store) {
    this.store = store;
  }

  /**
   * Saves a new version. A first version must begin a project of its own; an edit must name a
   * parent that is saved, readable, and of the same project and study. When this returns the
   * version is kept and reads back as it was written.
   */
  void save(StitchProject version) throws IOException {
    byte[] document = StitchProjectIO.write(version);
    String study = version.studyInstanceUid();
    if (version.parentVersionId() == null) {
      for (Listed other : list(study)) {
        if (other.project() != null && other.project().projectId().equals(version.projectId())) {
          throw new StitchProjectException(
              Kind.LINEAGE, "A first version cannot be added to a project that already has one.");
        }
      }
    } else {
      StitchProject parent = parent(version);
      if (!parent.projectId().equals(version.projectId())) {
        throw new StitchProjectException(
            Kind.LINEAGE, "The version this was edited from belongs to another stitch project.");
      }
    }
    try {
      store.put(study, version.versionId(), document);
    } catch (FileAlreadyExistsException e) {
      throw new StitchProjectException(
          Kind.EXISTS, "This version is already saved. Saved versions are never written over.");
    }
    if (!Arrays.equals(document, store.get(study, version.versionId()))) {
      throw new IOException("the stitch project did not read back as it was written");
    }
  }

  private StitchProject parent(StitchProject version) throws IOException {
    try {
      return load(version.studyInstanceUid(), version.parentVersionId());
    } catch (StitchProjectException e) {
      throw new StitchProjectException(
          Kind.LINEAGE,
          e.kind() == Kind.NOT_FOUND
              ? "The version this was edited from is not saved in this study."
              : "The version this was edited from cannot be read: " + e.getMessage());
    }
  }

  /** The version, checked. */
  StitchProject load(String studyInstanceUid, String versionId) throws IOException {
    byte[] document;
    try {
      document = store.get(studyInstanceUid, versionId);
    } catch (NoSuchFileException e) {
      throw new StitchProjectException(Kind.NOT_FOUND, "No such version of the stitch is saved.");
    }
    StitchProject p = StitchProjectIO.read(document);
    if (!p.versionId().equals(versionId) || !p.studyInstanceUid().equals(studyInstanceUid)) {
      throw new StitchProjectException(
          Kind.MALFORMED,
          "Not a usable stitch project: it is kept under another study or version.");
    }
    return p;
  }

  /** Every version kept for the study, oldest first, those that cannot be read last. */
  List<Listed> list(String studyInstanceUid) throws IOException {
    List<Listed> out = new ArrayList<>();
    for (String id : store.list(studyInstanceUid)) {
      try {
        out.add(new Listed(id, load(studyInstanceUid, id), null));
      } catch (StitchProjectException e) {
        out.add(new Listed(id, null, e.getMessage()));
      }
    }
    out.sort(
        Comparator.comparing((Listed l) -> l.project() == null)
            .thenComparing(
                l -> l.project() == null ? null : l.project().savedAt(),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Listed::versionId));
    return out;
  }
}
