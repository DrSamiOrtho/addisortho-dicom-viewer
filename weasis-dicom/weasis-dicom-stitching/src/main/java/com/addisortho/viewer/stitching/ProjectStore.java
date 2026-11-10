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

/**
 * Where the versions of stitch projects are kept: the whole of what stitching asks of storage. A
 * version is a document of a few kilobytes ({@link StitchProjectIO}) kept under its study and its
 * own identifier, written once and never changed. The store need not understand the document;
 * {@link StitchProjects} reads it, checks it and keeps the lineage.
 *
 * <p>An implementation must hold to this, and {@link StitchProjects} relies on it:
 *
 * <ul>
 *   <li><b>All or nothing.</b> After {@link #put} fails, or the viewer or the computer stops during
 *       it, the version is either complete or absent. A part-written document is never returned by
 *       {@link #get} nor named by {@link #list}.
 *   <li><b>Never replaced.</b> {@link #put} on an identifier that is already kept fails and leaves
 *       what is kept untouched, also when two callers put at once. Nothing here deletes.
 *   <li><b>Durable on return.</b> When {@link #put} returns, the document survives a restart and
 *       {@link #get} gives back exactly the bytes put.
 *   <li><b>Complete listing.</b> {@link #list} names every version kept for the study, so one that
 *       exists is never hidden; the order does not matter.
 * </ul>
 *
 * Both identifiers are safe as names: a study is a DICOM UID (digits and dots), a version a UUID.
 * Neither holds a patient's name. Calls may come from any thread.
 */
interface ProjectStore {

  /**
   * Keeps a new version.
   *
   * @throws java.nio.file.FileAlreadyExistsException when the identifier is already kept
   * @throws IOException when it failed; the version is then absent, or complete when only the last
   *     steps failed, and {@link #get} tells which
   */
  void put(String studyInstanceUid, String versionId, byte[] document) throws IOException;

  /**
   * The document exactly as it was put.
   *
   * @throws java.nio.file.NoSuchFileException when no such version is kept
   */
  byte[] get(String studyInstanceUid, String versionId) throws IOException;

  /** The identifiers of every version kept for the study; empty when there are none. */
  List<String> list(String studyInstanceUid) throws IOException;
}
