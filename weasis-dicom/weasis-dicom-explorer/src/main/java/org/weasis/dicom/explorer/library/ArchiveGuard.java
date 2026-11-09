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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import net.lingala.zip4j.model.FileHeader;

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
  private static final int COPY_BUFFER = 64 * 1024;

  enum Verdict {
    SAFE,
    TOO_MANY_FILES,
    FILE_TOO_LARGE,
    TOO_LARGE,
    /** Entries claim more compressed data than the archive holds: they overlap, a known attack. */
    OVERLAPPING,
    SUSPICIOUS_RATIO
  }

  /** Unpacking passed a limit. Whatever was already unpacked must not be used. */
  public static final class UnsafeArchive extends IOException {
    public UnsafeArchive(String message) {
      super(message);
    }
  }

  /** What one unpacking may still write. Shared by the threads of that unpacking. */
  public static final class Budget {
    private final AtomicLong left;
    private final BooleanSupplier cancelled;

    public Budget(long bytes, BooleanSupplier cancelled) {
      this.left = new AtomicLong(bytes);
      this.cancelled = cancelled;
    }

    /** As much as the archive limit allows and the folder's disk can spare. */
    public static Budget forFolder(Path folder, BooleanSupplier cancelled) throws IOException {
      long spare = Files.getFileStore(folder).getUsableSpace() - StudyStore.MIN_FREE_BYTES;
      return new Budget(Math.clamp(spare, 0, DEFAULT.maxTotalBytes()), cancelled);
    }

    void spend(int bytes) throws IOException {
      if (cancelled.getAsBoolean()) {
        throw new InterruptedIOException("Stopped");
      }
      if (left.addAndGet(-bytes) < 0) {
        throw new UnsafeArchive("The archive unpacks to more than can safely be written");
      }
    }
  }

  private ArchiveGuard() {}

  /**
   * Unpacks one entry, stopping at the size it declared: the directory read by {@link #inspect} is
   * only a claim, and an entry can expand far beyond it.
   */
  public static void copy(InputStream in, Path target, long declaredSize, Budget budget)
      throws IOException {
    byte[] buffer = new byte[COPY_BUFFER];
    long written = 0;
    try (OutputStream out = Files.newOutputStream(target)) {
      for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
        written += n;
        if (written > declaredSize) {
          throw new UnsafeArchive("An archive entry is larger than it declares");
        }
        budget.spend(n);
        out.write(buffer, 0, n);
      }
    }
  }

  static Verdict inspect(ZipFile zip, long archiveBytes, Limits limits) {
    return judge(
        zip.size(),
        zip.stream().map(e -> new long[] {e.getSize(), e.getCompressedSize()}).iterator(),
        archiveBytes,
        limits);
  }

  /**
   * @param sizes the unpacked and the compressed size of each entry
   */
  private static Verdict judge(
      int count, Iterator<long[]> sizes, long archiveBytes, Limits limits) {
    if (count > limits.maxEntries()) {
      return Verdict.TOO_MANY_FILES;
    }
    long total = 0;
    long compressed = 0;
    while (sizes.hasNext()) {
      long[] entry = sizes.next();
      if (entry[0] < 0 || entry[0] > limits.maxEntryBytes()) {
        return Verdict.FILE_TOO_LARGE;
      }
      total = saturatingAdd(total, entry[0]);
      compressed = saturatingAdd(compressed, Math.max(0, entry[1]));
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
   * Whether the archive must not be unpacked. The JDK cannot open a password-protected archive, so
   * that one is judged from zip4j's reading of the same directory. One that neither can read is not
   * refused here: unpacking it is bounded by {@link #copy} and reports the failure.
   */
  public static boolean isRefused(File archive) {
    return isRefused(archive, DEFAULT);
  }

  static boolean isRefused(File archive, Limits limits) {
    try (ZipFile zip = new ZipFile(archive, ZipFile.OPEN_READ, StandardCharsets.ISO_8859_1)) {
      return inspect(zip, archive.length(), limits) != Verdict.SAFE;
    } catch (IOException | RuntimeException e) {
      return refusedByZip4j(archive, limits);
    }
  }

  private static boolean refusedByZip4j(File archive, Limits limits) {
    try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(archive)) {
      List<FileHeader> headers = zip.getFileHeaders();
      Iterator<long[]> sizes =
          headers.stream()
              .map(h -> new long[] {h.getUncompressedSize(), h.getCompressedSize()})
              .iterator();
      return judge(headers.size(), sizes, archive.length(), limits) != Verdict.SAFE;
    } catch (IOException | RuntimeException e) {
      return false;
    }
  }
}
