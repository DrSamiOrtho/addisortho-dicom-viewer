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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weasis.dicom.explorer.library.ArchiveGuard;

/** The viewer's own unpacking must stop at what an archive declared and what the disk can spare. */
class DicomZipBoundsTest {
  private static final int ENTRY_BYTES = 1 << 20;
  private static final int CENTRAL_SIGNATURE = 0x02014b50;
  private static final int CENTRAL_UNCOMPRESSED_SIZE = 24;

  @TempDir Path tmp;

  private File zipOfZeros() throws IOException {
    Path file = tmp.resolve("zeros.zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      out.putNextEntry(new ZipEntry("IM1"));
      out.write(new byte[ENTRY_BYTES]);
      out.closeEntry();
    }
    return file.toFile();
  }

  /** Makes the archive's directory claim the entry is far smaller than it really unpacks to. */
  private File understated(File zip) throws IOException {
    byte[] raw = Files.readAllBytes(zip.toPath());
    ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    for (int i = 0; i < raw.length - 4; i++) {
      if (buffer.getInt(i) == CENTRAL_SIGNATURE) {
        buffer.putInt(i + CENTRAL_UNCOMPRESSED_SIZE, 10);
      }
    }
    Files.write(zip.toPath(), raw);
    return zip;
  }

  private Path out() throws IOException {
    return Files.createDirectories(tmp.resolve("out"));
  }

  private static ArchiveGuard.Budget budget(long bytes) {
    return new ArchiveGuard.Budget(bytes, () -> false);
  }

  @Test
  void anEntryLargerThanDeclaredIsStopped() throws IOException {
    File zip = understated(zipOfZeros());
    Path out = out();

    assertThrows(
        ArchiveGuard.UnsafeArchive.class,
        () -> DicomZipMediaIO.extractFast(zip, out, _ -> {}, budget(1L << 30)));
    assertTrue(Files.size(out.resolve("IM1")) <= 10);
  }

  @Test
  void unpackingStopsWhenTheBudgetIsSpent() throws IOException {
    File zip = zipOfZeros();

    assertThrows(
        ArchiveGuard.UnsafeArchive.class,
        () -> DicomZipMediaIO.extractFast(zip, out(), _ -> {}, budget(1000)));
  }

  @Test
  void stoppingIsNotTreatedAsAReasonToTryAnotherReader() throws IOException {
    File zip = zipOfZeros();

    assertThrows(
        InterruptedIOException.class,
        () ->
            DicomZipMediaIO.extractFast(
                zip, out(), _ -> {}, new ArchiveGuard.Budget(1L << 30, () -> true)));
  }

  @Test
  void anHonestArchiveUnpacksInFull() throws IOException {
    File zip = zipOfZeros();
    Path out = out();

    assertTrue(DicomZipMediaIO.extractFast(zip, out, _ -> {}, budget(1L << 30)));
    assertEquals(ENTRY_BYTES, Files.size(out.resolve("IM1")));
  }
}
