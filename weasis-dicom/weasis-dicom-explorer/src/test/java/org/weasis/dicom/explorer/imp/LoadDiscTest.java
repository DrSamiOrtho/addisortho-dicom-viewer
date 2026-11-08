/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadDiscTest {

  @TempDir Path disc;
  @TempDir Path copy;

  @Test
  void copiesTheImagesAndLeavesOutTheViewer() throws IOException {
    Files.createDirectories(disc.resolve("DICOM/ST000/SE000"));
    Files.createDirectories(disc.resolve("Viewer"));
    Files.write(disc.resolve("DICOM/ST000/SE000/IM000"), new byte[300]);
    Files.write(disc.resolve("DICOM/ST000/SE000/IM001"), new byte[300]);
    Files.write(disc.resolve("Viewer/viewer.exe"), new byte[1000]);
    Files.write(disc.resolve("autorun.inf"), new byte[10]);
    Files.write(disc.resolve("DICOMDIR"), new byte[10]);

    List<Integer> progress = new ArrayList<>();
    LoadDisc.copy(disc, copy, progress::add, () -> false);

    try (Stream<Path> walk = Files.walk(copy)) {
      assertEquals(
          List.of("DICOM/ST000/SE000/IM000", "DICOM/ST000/SE000/IM001"),
          walk.filter(Files::isRegularFile)
              .map(f -> copy.relativize(f).toString().replace('\\', '/'))
              .sorted()
              .toList());
    }
    assertEquals(0, progress.getFirst());
    assertEquals(100, progress.getLast());
  }

  @Test
  void stopsWhenCancelled() throws IOException {
    Files.write(disc.resolve("IM000"), new byte[10]);
    LoadDisc.copy(disc, copy, _ -> {}, () -> true);
    assertFalse(Files.exists(copy.resolve("IM000")));
  }

  @Test
  void skipsTheDicomDirAndProgramFiles() {
    assertTrue(LoadDisc.isSkipped("DICOMDIR"));
    assertTrue(LoadDisc.isSkipped("dicomdir"));
    assertTrue(LoadDisc.isSkipped("Viewer/lib/x.dll"));
    assertFalse(LoadDisc.isSkipped("DICOM/IM000"));
    assertFalse(LoadDisc.isSkipped("IMAGES/IMG0001.dcm"));
  }
}
