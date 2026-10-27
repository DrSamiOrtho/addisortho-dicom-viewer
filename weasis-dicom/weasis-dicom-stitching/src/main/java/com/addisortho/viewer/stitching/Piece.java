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
import java.util.Locale;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * One X-ray piece: its header, its size and spacing, and how to read its pixels again. The full
 * pixels are read twice only: once to build the small matching copies, once while the stitch is
 * written; they are never kept (design section 9).
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
  private MatchImage match;
  private Gray level;

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

  /** Builds the matching copies and the 2 mm level copy (one full read). */
  synchronized void prepare() throws IOException {
    if (match != null) {
      return;
    }
    float[] v = read();
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

  /** Full-resolution values in output polarity: MONOCHROME1 is negated so bone is bright. */
  float[] read() throws IOException {
    float[] v = source.read();
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
