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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * A {@link ProjectStore} in a folder, for tests: one folder per study, one file per version. A
 * version is written beside its place under a hidden name, forced to the disk, then moved into
 * place in one step, so a version that can be listed is always whole. It refuses to replace a
 * version only against callers of this one object (the check and the move are made under its lock);
 * a store shared between processes needs a stronger refusal than this.
 */
final class FolderProjectStore implements ProjectStore {

  private static final String SUFFIX = ".stitch.json";

  private final Path root;

  FolderProjectStore(Path root) {
    this.root = root;
  }

  Path file(String studyInstanceUid, String versionId) {
    return root.resolve(studyInstanceUid).resolve(versionId + SUFFIX);
  }

  @Override
  public synchronized void put(String studyInstanceUid, String versionId, byte[] document)
      throws IOException {
    Path target = file(studyInstanceUid, versionId);
    if (Files.exists(target)) {
      throw new FileAlreadyExistsException(versionId);
    }
    Files.createDirectories(target.getParent());
    Path part = target.resolveSibling("." + versionId + ".part");
    try (FileChannel out =
        FileChannel.open(
            part,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      ByteBuffer bytes = ByteBuffer.wrap(document);
      while (bytes.hasRemaining()) {
        out.write(bytes);
      }
      out.force(true);
    }
    Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
  }

  @Override
  public byte[] get(String studyInstanceUid, String versionId) throws IOException {
    Path file = file(studyInstanceUid, versionId);
    if (!Files.isRegularFile(file)) {
      throw new NoSuchFileException(versionId);
    }
    return Files.readAllBytes(file);
  }

  @Override
  public List<String> list(String studyInstanceUid) throws IOException {
    Path folder = root.resolve(studyInstanceUid);
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (var files = Files.list(folder)) {
      return files
          .map(f -> f.getFileName().toString())
          .filter(n -> n.endsWith(SUFFIX) && !n.startsWith("."))
          .map(n -> n.substring(0, n.length() - SUFFIX.length()))
          .toList();
    }
  }
}
