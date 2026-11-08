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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DicomZipMediaIOTest {

  @TempDir Path tmp;

  private File zip(String... namesAndContents) throws IOException {
    File file = tmp.resolve("study.zip").toFile();
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
      for (int i = 0; i < namesAndContents.length; i += 2) {
        out.putNextEntry(new ZipEntry(namesAndContents[i]));
        out.write(namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }

  @Test
  void unzipsImagesAndLeavesOutTheBundledViewer() throws IOException {
    File file =
        zip(
            "DICOMDIR", "dir",
            "DICOM/S1/IM0001", "image one",
            "DICOM/S1/IM0002.dcm", "image two",
            "Viewer/viewer.exe", "program",
            "Viewer/lib.dll", "library",
            "__MACOSX/DICOM/._IM0001", "metadata",
            "autorun.inf", "autorun");
    Path out = Files.createDirectory(tmp.resolve("out"));
    List<Integer> percents = new CopyOnWriteArrayList<>();

    assertTrue(DicomZipMediaIO.extractFast(file, out, percents::add));

    assertEquals(0, percents.getFirst());
    assertEquals(100, Collections.max(percents));
    assertArrayEquals(
        "image one".getBytes(StandardCharsets.UTF_8),
        Files.readAllBytes(out.resolve("DICOM/S1/IM0001")));
    assertTrue(Files.exists(out.resolve("DICOM/S1/IM0002.dcm")));
    assertTrue(Files.exists(out.resolve("DICOMDIR")));
    assertFalse(Files.exists(out.resolve("Viewer")));
    assertFalse(Files.exists(out.resolve("__MACOSX")));
    assertFalse(Files.exists(out.resolve("autorun.inf")));
  }

  @Test
  void neverWritesOutsideTheTargetFolder() throws IOException {
    File file = zip("../escaped", "bad", "IM0001", "image");
    Path out = Files.createDirectory(tmp.resolve("out"));

    assertTrue(DicomZipMediaIO.extractFast(file, out, _ -> {}));

    assertFalse(Files.exists(tmp.resolve("escaped")));
    assertTrue(Files.exists(out.resolve("IM0001")));
  }

  @Test
  void reportsFailureSoTheCallerFallsBack() throws IOException {
    File notZip = tmp.resolve("broken.zip").toFile();
    try (OutputStream out = Files.newOutputStream(notZip.toPath())) {
      out.write("not a zip".getBytes(StandardCharsets.UTF_8));
    }
    Path out = Files.createDirectory(tmp.resolve("out"));
    assertFalse(DicomZipMediaIO.extractFast(notZip, out, _ -> {}));
  }

  @Test
  void skipsOnlyFilesThatAreNeverImages() {
    assertTrue(DicomZipMediaIO.isSkipped("CD\\Viewer\\RadiAnt.EXE"));
    assertTrue(DicomZipMediaIO.isSkipped("sub/.DS_Store"));
    assertFalse(DicomZipMediaIO.isSkipped("DICOM/IM0001"));
    assertFalse(DicomZipMediaIO.isSkipped("DICOM/IM0001.dcm"));
    assertFalse(DicomZipMediaIO.isSkipped("DICOMDIR"));
    assertFalse(DicomZipMediaIO.isSkipped("report.pdf"));
  }
}
