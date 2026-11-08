/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides from its directory alone whether an archive is safe to unpack: a few kilobytes can
 * otherwise expand until the disk is full. The limits sit far above any real study; the sizes read
 * here are only claims, so whoever unpacks must also stop at the size an entry declared.
 */
public final class ArchiveGuard {

  /**
   * @param ratioCheckAbove the expansion ratio is only judged for archives larger than this: small
   *     blank test images compress extremely well and harm nothing
   */
  record Limits(
      int maxEntries, long maxEntryBytes, long maxTotalBytes, long ratioCheckAbove, int maxRatio) {}

  static final Limits DEFAULT = new Limits(200_000, 64L << 30, 4L << 40, 2L << 30, 200);

  enum Verdict {
    SAFE,
    TOO_MANY_FILES,
    FILE_TOO_LARGE,
    TOO_LARGE,
    /** Entries claim more compressed data than the archive holds: they overlap, a known attack. */
    OVERLAPPING,
    SUSPICIOUS_RATIO
  }

  private ArchiveGuard() {}

  static Verdict inspect(ZipFile zip, long archiveBytes, Limits limits) {
    if (zip.size() > limits.maxEntries()) {
      return Verdict.TOO_MANY_FILES;
    }
    long total = 0;
    long compressed = 0;
    Enumeration<? extends ZipEntry> entries = zip.entries();
    while (entries.hasMoreElements()) {
      ZipEntry entry = entries.nextElement();
      long size = entry.getSize();
      if (size < 0 || size > limits.maxEntryBytes()) {
        return Verdict.FILE_TOO_LARGE;
      }
      total = saturatingAdd(total, size);
      compressed = saturatingAdd(compressed, Math.max(0, entry.getCompressedSize()));
    }
    if (total > limits.maxTotalBytes()) {
      return Verdict.TOO_LARGE;
    }
    if (compressed > archiveBytes) {
      return Verdict.OVERLAPPING;
    }
    if (total > limits.ratioCheckAbove() && total / Math.max(1, compressed) > limits.maxRatio()) {
      return Verdict.SUSPICIOUS_RATIO;
    }
    return Verdict.SAFE;
  }

  private static long saturatingAdd(long a, long b) {
    long sum = a + b;
    return sum < 0 ? Long.MAX_VALUE : sum;
  }

  /**
   * Whether the archive must not be unpacked. One that cannot be read at all is not refused here:
   * the unpacking reports that itself.
   */
  public static boolean isRefused(File archive) {
    try (ZipFile zip = new ZipFile(archive, ZipFile.OPEN_READ, StandardCharsets.ISO_8859_1)) {
      return inspect(zip, archive.length(), DEFAULT) != Verdict.SAFE;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }
}
