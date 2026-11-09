/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.imp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadDiscLostFilesTest {
  @TempDir Path tmp;

  @Test
  void aFileTheDiscWillNotGiveUpIsCountedAndTheRestCopied() throws IOException {
    Path disc = Files.createDirectories(tmp.resolve("disc"));
    Files.writeString(disc.resolve("IM1"), "one");
    Path lost = Files.writeString(disc.resolve("IM2"), "two");
    Files.writeString(disc.resolve("IM3"), "three");
    Files.setPosixFilePermissions(lost, PosixFilePermissions.fromString("---------"));
    Path out = Files.createDirectories(tmp.resolve("out"));

    LoadDisc.Copied copied = LoadDisc.copy(disc, out, _ -> {}, () -> false);

    assertEquals(1, copied.failed());
    assertEquals("IM2", copied.firstFailed());
    assertTrue(Files.exists(out.resolve("IM1")));
    assertTrue(Files.exists(out.resolve("IM3")));
    assertFalse(Files.exists(out.resolve("IM2")));
  }

  @Test
  void aDiscThatCannotBeListedIsReportedNotThrown() throws IOException {
    LoadDisc.Copied copied =
        LoadDisc.copyReportingFailure(
            tmp.resolve("ejected"),
            Files.createDirectories(tmp.resolve("out")),
            _ -> {},
            () -> false);

    assertEquals(1, copied.failed());
  }

  @Test
  void aCompleteCopyReportsNothingLost() throws IOException {
    Path disc = Files.createDirectories(tmp.resolve("disc"));
    Files.writeString(disc.resolve("IM1"), "one");

    LoadDisc.Copied copied =
        LoadDisc.copy(disc, Files.createDirectories(tmp.resolve("out")), _ -> {}, () -> false);

    assertEquals(0, copied.failed());
  }
}
