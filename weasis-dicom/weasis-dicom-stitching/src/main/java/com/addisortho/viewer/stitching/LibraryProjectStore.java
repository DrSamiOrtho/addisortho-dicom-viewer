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
import java.util.List;
import java.util.function.Supplier;
import org.weasis.dicom.explorer.library.ProjectDocuments;
import org.weasis.dicom.explorer.library.StudyStore;

/**
 * Stitch projects kept by the study library of this computer, beside the studies and apart from
 * their images ({@link ProjectDocuments} says where and what it guarantees). The store is asked for
 * on every call, so after the library is pointed at another folder the projects are those of that
 * folder; nothing is moved, as the studies are not moved either.
 */
final class LibraryProjectStore implements ProjectStore {

  static final String KIND = "stitch"; // NON-NLS

  private final Supplier<StudyStore> library;

  LibraryProjectStore(Supplier<StudyStore> library) {
    this.library = library;
  }

  private ProjectDocuments documents() {
    return library.get().projects(KIND);
  }

  @Override
  public void put(String studyInstanceUid, String versionId, byte[] document) throws IOException {
    documents().put(studyInstanceUid, versionId, document);
  }

  @Override
  public byte[] get(String studyInstanceUid, String versionId) throws IOException {
    return documents().get(studyInstanceUid, versionId);
  }

  @Override
  public List<String> list(String studyInstanceUid) throws IOException {
    return documents().list(studyInstanceUid);
  }
}
