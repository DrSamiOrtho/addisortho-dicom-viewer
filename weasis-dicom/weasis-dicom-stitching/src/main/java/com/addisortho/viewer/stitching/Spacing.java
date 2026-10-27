/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/**
 * A piece's pixel size in mm and where it comes from (design section 3). {@link #weasis} repeats
 * the rule of {@code DicomImageElement.initPixelConfiguration}, so the stitch measures as the
 * pieces do in the viewer; {@link #page} repeats the templating page's {@code dicomEstimate}, which
 * can differ, and the window shows both when they do.
 *
 * @param colMm horizontal pixel size (the second value of PixelSpacing)
 * @param rowMm vertical pixel size (the first value)
 */
record Spacing(double colMm, double rowMm, Basis basis, String note) {

  /** What the number means. */
  enum Basis {
    /** PixelSpacing as the file states it (calibrated by the machine, or not said). */
    PIXEL_SPACING("pixel spacing as stated in the file"),
    /** ImagerPixelSpacing divided by the machine's magnification factor or SID over SOD. */
    MAGNIFIED("detector spacing divided by the machine's magnification factor"),
    /** ImagerPixelSpacing with no magnification taken off: the size at the detector. */
    DETECTOR("detector spacing, magnification not taken off"),
    SCANNER("nominal scanned pixel spacing"),
    NONE("no pixel spacing");

    final String words;

    Basis(String words) {
      this.words = words;
    }
  }

  private static final Set<String> MAG_MODALITIES =
      Set.of("CR", "DX", "IO", "MG", "PX", "RF", "XA");

  /**
   * A working pixel size for images without spacing, so matching runs on a bounded grid (a 1 mm
   * guess would double a CR plate on the 0.5 mm grid and exhaust a 1 GB heap: Astra, code review
   * P2). Never written to the output, which then carries no PixelSpacing.
   */
  static final double UNSCALED_MM = 0.15;

  boolean hasScale() {
    return basis != Basis.NONE;
  }

  boolean square() {
    return Math.abs(colMm / rowMm - 1) <= 0.005;
  }

  /** The viewer's rule, in the viewer's order. */
  static Spacing weasis(Attributes a) {
    double[] ps = pair(a, Tag.PixelSpacing);
    if (ps != null) {
      String type = a.getString(Tag.PixelSpacingCalibrationType, "");
      return new Spacing(
          ps[1],
          ps[0],
          Basis.PIXEL_SPACING,
          "pixel spacing %s mm%s".formatted(fmt(ps), type.isEmpty() ? "" : " (" + type + ")"));
    }
    double[] ips = pair(a, Tag.ImagerPixelSpacing);
    if (ips != null) {
      Optional<Double> mag =
          MAG_MODALITIES.contains(a.getString(Tag.Modality, ""))
              ? magnification(a)
              : Optional.empty();
      if (mag.isPresent()) {
        double m = mag.get();
        return new Spacing(
            ips[1] / m,
            ips[0] / m,
            Basis.MAGNIFIED,
            String.format(Locale.ROOT, "detector %s mm / magnification %.4f", fmt(ips), m));
      }
      return new Spacing(
          ips[1],
          ips[0],
          Basis.DETECTOR,
          "detector %s mm, magnification unknown".formatted(fmt(ips)));
    }
    double[] ns = pair(a, Tag.NominalScannedPixelSpacing);
    if (ns != null) {
      return new Spacing(ns[1], ns[0], Basis.SCANNER, "scanner %s mm".formatted(fmt(ns)));
    }
    return unscaled(a);
  }

  // keeps the pixel aspect ratio as the viewer does (initPixelAspectRatio: rows over columns)
  private static Spacing unscaled(Attributes a) {
    int[] ar = a.getInts(Tag.PixelAspectRatio);
    boolean aspect = ar != null && ar.length == 2 && ar[0] > 0 && ar[1] > 0 && ar[0] != ar[1];
    double rowOverCol = aspect ? (double) ar[0] / ar[1] : 1;
    return new Spacing(
        UNSCALED_MM,
        UNSCALED_MM * rowOverCol,
        Basis.NONE,
        aspect
            ? "no pixel spacing; pixel aspect %d:%d kept".formatted(ar[0], ar[1])
            : "no pixel spacing in the file");
  }

  /** The templating page's rule ({@code app.js dicomEstimate}): rows only, as the page uses. */
  static Optional<Double> page(Attributes a) {
    double[] ps = pair(a, Tag.PixelSpacing);
    double[] ips = pair(a, Tag.ImagerPixelSpacing);
    String type = a.getString(Tag.PixelSpacingCalibrationType, "").toUpperCase(Locale.ROOT);
    if (ps != null
        && (type.equals("GEOMETRY")
            || type.equals("FIDUCIAL")
            || (ips != null && Math.abs(ps[0] / ips[0] - 1) > 0.002))) {
      return Optional.of(ps[0]);
    }
    if (ips != null) {
      double m = a.getDouble(Tag.EstimatedRadiographicMagnificationFactor, Double.NaN);
      return Optional.of(m > 0.9 && m < 2 ? ips[0] / m : ips[0]);
    }
    return ps == null ? Optional.empty() : Optional.of(ps[0]);
  }

  /** A sentence when the viewer and the templating page would scale this piece differently. */
  static Optional<String> disagreement(Attributes a) {
    Spacing w = weasis(a);
    Optional<Double> p = page(a);
    if (!w.hasScale() || p.isEmpty() || Math.abs(p.get() / w.rowMm - 1) <= 0.005) {
      return Optional.empty();
    }
    return Optional.of(
        String.format(
            Locale.ROOT,
            "the viewer measures this piece at %.4f mm per pixel, the templating page at %.4f;"
                + " neither is the size at the bone",
            w.rowMm,
            p.get()));
  }

  private static Optional<Double> magnification(Attributes a) {
    double m = a.getDouble(Tag.EstimatedRadiographicMagnificationFactor, Double.NaN);
    if (m > 0) {
      return Optional.of(m);
    }
    double sid = a.getDouble(Tag.DistanceSourceToDetector, Double.NaN);
    double sod = a.getDouble(Tag.DistanceSourceToPatient, Double.NaN);
    return sid > 0 && sod > 0 ? Optional.of(sid / sod) : Optional.empty();
  }

  private static double[] pair(Attributes a, int tag) {
    double[] v = a.getDoubles(tag);
    return v != null && v.length == 2 && v[0] > 0 && v[1] > 0 ? v : null;
  }

  private static String fmt(double[] v) {
    return v[0] == v[1]
        ? String.format(Locale.ROOT, "%.4f", v[0])
        : String.format(Locale.ROOT, "%.4f x %.4f", v[0], v[1]);
  }
}
