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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * One X-ray piece: its header, its size and spacing, and how to read its pixels again. The full
 * pixels are read three times only: once for the pictures in the window, once to build the small
 * matching copies, once while the stitch is written; they are never kept (design section 9).
 */
final class Piece {

  /** Reads the piece's modality values, row-major; the caller owns the array. */
  @FunctionalInterface
  interface PixelSource {
    float[] read() throws IOException;
  }

  final Attributes header;
  final int width;
  final int height;
  final Spacing spacing;
  final boolean inverse;
  private final PixelSource source;
  // the pictures load in the background while Line up may read the same image
  private final Object readLock = new Object();
  private MatchImage match;
  private Gray level;
  private String pixelHash;

  Piece(Attributes header, int width, int height, PixelSource source) {
    this.header = header;
    this.width = width;
    this.height = height;
    this.spacing = Spacing.weasis(header);
    this.inverse = "MONOCHROME1".equals(header.getString(Tag.PhotometricInterpretation));
    this.source = source;
  }

  String sopInstanceUid() {
    return header.getString(Tag.SOPInstanceUID, "");
  }

  String sopClassUid() {
    return header.getString(Tag.SOPClassUID, "");
  }

  double widthMm() {
    return width * spacing.colMm();
  }

  double heightMm() {
    return height * spacing.rowMm();
  }

  /** A short label for the window: series, instance, size. */
  String label() {
    String desc = header.getString(Tag.SeriesDescription, "");
    String inst = header.getString(Tag.InstanceNumber, "?");
    String size =
        spacing.hasScale()
            ? String.format(Locale.ROOT, "%.0f x %.0f mm", widthMm(), heightMm())
            : "%d x %d pixels, no scale".formatted(width, height);
    return "%s%s image %s, %s".formatted(desc, desc.isEmpty() ? "" : ",", inst, size).trim();
  }

  /** What the file says that helps tell this image from the others; empty values are left out. */
  List<String> details() {
    List<String> out = new ArrayList<>();
    String series = header.getString(Tag.SeriesNumber, "");
    if (!series.isEmpty()) {
      out.add("series " + series);
    }
    String time = header.getString(Tag.AcquisitionTime, header.getString(Tag.ContentTime, ""));
    if (time.length() >= 6) {
      out.add(
          "taken %s:%s:%s"
              .formatted(time.substring(0, 2), time.substring(2, 4), time.substring(4, 6)));
    }
    String part = header.getString(Tag.BodyPartExamined, "");
    if (!part.isEmpty()) {
      out.add(part);
    }
    String view = header.getString(Tag.ViewPosition, "");
    if (!view.isEmpty()) {
      out.add("view " + view);
    }
    String side = header.getString(Tag.ImageLaterality, header.getString(Tag.Laterality, ""));
    if (!side.isEmpty()) {
      out.add("side " + side);
    }
    String comments = header.getString(Tag.ImageComments, "");
    if (!comments.isEmpty()) {
      out.add(comments);
    }
    return out;
  }

  /** Builds the matching copies and the 2 mm level copy (one full read). */
  synchronized void prepare() throws IOException {
    if (match != null) {
      return;
    }
    float[] v = read();
    pixelHash = hash(v, width, height);
    match = MatchImage.of(v, width, height, spacing.colMm(), spacing.rowMm(), false);
    level = Gray.resample(v, width, height, spacing.colMm(), spacing.rowMm(), MatchImage.COARSE_MM);
  }

  synchronized MatchImage match() {
    return match;
  }

  /** The 2 mm copy in output polarity (bone bright), not normalised: for exposure and preview. */
  synchronized Gray level() {
    return level;
  }

  /**
   * SHA-256 of the pixels as the stitch uses them, known once {@link #prepare} has read them: the
   * width and height as 32-bit integers, then every value of {@link #read} row by row as a 32-bit
   * float, all big-endian. It follows the decoded values, not the file, so a copy of the same image
   * in another transfer syntax is the same source and any change to a pixel is another.
   */
  synchronized String pixelHash() {
    return pixelHash;
  }

  static String hash(float[] values, int width, int height) {
    try {
      MessageDigest sha = MessageDigest.getInstance("SHA-256");
      ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
      buffer.putInt(width).putInt(height);
      for (float v : values) {
        if (buffer.remaining() < Float.BYTES) {
          sha.update(buffer.flip());
          buffer.clear();
        }
        buffer.putFloat(v);
      }
      sha.update(buffer.flip());
      return HexFormat.of().formatHex(sha.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e); // every Java runtime has SHA-256
    }
  }

  /** Full-resolution values in output polarity: MONOCHROME1 is negated so bone is bright. */
  float[] read() throws IOException {
    float[] v;
    synchronized (readLock) {
      v = source.read();
    }
    if (v.length != width * height) {
      throw new IOException("the piece's pixels do not match its size");
    }
    if (inverse) {
      for (int i = 0; i < v.length; i++) {
        v[i] = -v[i];
      }
    }
    return v;
  }
}
