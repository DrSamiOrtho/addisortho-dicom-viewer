/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SavedFileTest {
  private static final byte[] CONTENT = {1, 2, 3};

  @TempDir Path tmp;

  private long filesIn(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.count();
    }
  }

  @Test
  void createsTheFolderAndPublishesTheFile() throws IOException {
    Path destination = tmp.resolve("patient/study/image.png");

    SavedFile.write(
        destination,
        staged -> {
          Files.write(staged, CONTENT);
          return true;
        });

    assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    assertEquals(1, filesIn(destination.getParent()));
  }

  @Test
  void keepsTheExtensionThatPicksTheImageEncoder() {
    assertTrue(SavedFile.stagingPath(tmp.resolve("a.jpg")).toString().endsWith(".jpg"));
    assertEquals(tmp, SavedFile.stagingPath(tmp.resolve("a.jpg")).getParent());
  }

  @Test
  void aWriterThatReportsFailureFailsAndLeavesNothing() throws IOException {
    Path destination = tmp.resolve("image.png");

    assertThrows(
        IOException.class,
        () ->
            SavedFile.write(
                destination,
                staged -> {
                  Files.write(staged, CONTENT);
                  return false;
                }));

    assertFalse(Files.exists(destination));
    assertEquals(0, filesIn(tmp));
  }

  @Test
  void aWriterThatClaimsSuccessWithoutAFileFails() {
    Path destination = tmp.resolve("image.png");
    assertThrows(IOException.class, () -> SavedFile.write(destination, staged -> true));
    assertFalse(Files.exists(destination));
  }

  @Test
  void anEmptyFileIsNotASavedFile() throws IOException {
    Path destination = tmp.resolve("image.png");

    assertThrows(
        IOException.class,
        () ->
            SavedFile.write(
                destination,
                staged -> {
                  Files.createFile(staged);
                  return true;
                }));

    assertFalse(Files.exists(destination));
    assertEquals(0, filesIn(tmp));
  }

  @Test
  void aFailedRepeatLeavesTheEarlierExportAlone() throws IOException {
    Path destination = tmp.resolve("image.png");
    Files.write(destination, CONTENT);

    assertThrows(
        IOException.class,
        () ->
            SavedFile.write(
                destination,
                staged -> {
                  throw new IOException("Disk full");
                }));

    assertArrayEquals(CONTENT, Files.readAllBytes(destination));
    assertEquals(1, filesIn(tmp));
  }

  @Test
  void theReasonIsNotJustTheFolderName() {
    String folder = "/some/folder";
    assertFalse(SavedFile.reason(new AccessDeniedException(folder)).contains(folder));
    assertEquals(
        "No space left on device",
        SavedFile.reason(new FileSystemException(folder, null, "No space left on device")));
    assertEquals("Broken", SavedFile.reason(new IOException("Broken")));
  }

  @Test
  void aRepeatReplacesTheEarlierExport() throws IOException {
    Path destination = tmp.resolve("image.png");
    Files.write(destination, new byte[] {9});

    SavedFile.write(
        destination,
        staged -> {
          Files.write(staged, CONTENT);
          return true;
        });

    assertArrayEquals(CONTENT, Files.readAllBytes(destination));
  }

  @Test
  void aDestinationThatCannotBeAFolderFails() throws IOException {
    Path notAFolder = tmp.resolve("file");
    Files.write(notAFolder, CONTENT);

    assertThrows(
        IOException.class, () -> SavedFile.write(notAFolder.resolve("image.png"), staged -> true));
  }
}
