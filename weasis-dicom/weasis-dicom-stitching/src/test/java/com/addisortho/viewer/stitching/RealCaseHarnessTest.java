/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * A development harness, not a test of the build: runs the matcher on real pieces kept outside the
 * repository and writes what drove each seam. Enabled only when {@code STITCH_CASES} names a folder
 * of single-frame uncompressed DICOM pieces; {@code STITCH_PIECES} lists their InstanceNumbers in
 * order head to foot (default 1,2,3,4); {@code STITCH_OUT} receives the pictures and, when set, the
 * stitched DICOM. Nothing it writes carries a patient name; the output DICOM does, so keep it
 * local.
 */
class RealCaseHarnessTest {

  @Test
  @EnabledIfEnvironmentVariable(named = "STITCH_CASES", matches = ".+")
  void describeTheRealCase() throws IOException {
    Path dir = Path.of(System.getenv("STITCH_CASES"));
    Path out =
        Path.of(System.getenv().getOrDefault("STITCH_OUT", dir.resolve("harness").toString()));
    Files.createDirectories(out);
    String[] wanted = System.getenv().getOrDefault("STITCH_PIECES", "1,2,3,4").split(",");
    List<Piece> pieces = new ArrayList<>();
    for (String w : wanted) {
      pieces.add(piece(dir, Integer.parseInt(w.trim())));
    }
    long t0 = System.nanoTime();
    for (Piece p : pieces) {
      p.prepare();
    }
    System.out.printf(
        Locale.ROOT,
        "prepared %d pieces in %.1f s%n",
        pieces.size(),
        (System.nanoTime() - t0) / 1e9);
    List<Seam> bothSeams = new ArrayList<>();
    for (int i = 0; i + 1 < pieces.size(); i++) {
      MatchImage up = pieces.get(i).match();
      MatchImage down = pieces.get(i + 1).match();
      System.out.printf(
          Locale.ROOT,
          "%n=== seam %d (piece %s under piece %s)%n",
          i + 1,
          wanted[i + 1],
          wanted[i]);
      for (SeamMatcher.Coarse c : SeamMatcher.coarsePeaks(up, down, SeamMatcher.Band.WHOLE, 3)) {
        System.out.printf(
            Locale.ROOT,
            "  rough candidate: dx %.1f dy %.1f score %.3f uniqueness %.2f slide %.0f/%.0f mm%n",
            c.dx(),
            c.dy(),
            c.score(),
            c.uniqueness(),
            c.slideXMm(),
            c.slideYMm());
      }
      for (SeamMatcher.Band band :
          List.of(
              SeamMatcher.Band.WHOLE, SeamMatcher.Band.LEFT_HALF, SeamMatcher.Band.RIGHT_HALF)) {
        long t = System.nanoTime();
        Seam s = SeamMatcher.match(up, down, band);
        report(band.words(), s, (System.nanoTime() - t) / 1e9, up);
        picture(
            out.resolve("seam%d-%s.png".formatted(i + 1, band.words().replace(' ', '-'))),
            up,
            down,
            s);
      }
      long t = System.nanoTime();
      Seam both = SeamMatcher.matchBothLegs(up, down);
      report("both legs", both, (System.nanoTime() - t) / 1e9, up);
      bothSeams.add(both);
    }
    boolean failed = bothSeams.stream().anyMatch(s -> s.status() == Seam.Status.FAILED);
    Stitch stitch =
        new Stitch(
            pieces,
            bothSeams,
            new Stitch.Choices("AP", "standing", Stitch.Leg.BOTH, SeamMatcher.Band.WHOLE),
            null,
            failed);
    System.out.printf(
        Locale.ROOT, "%n=== stitch (Both, whole width)%s%n", failed ? ", unverified" : "");
    for (int i = 0; i < bothSeams.size(); i++) {
      System.out.printf(
          Locale.ROOT,
          "  cut %d: %.1f mm down the upper piece, %.1f mm down the stitch; %s%n",
          i + 1,
          stitch.cut(i).yMm(),
          stitch.seamY(i),
          stitch.cut(i).note());
    }
    long t = System.nanoTime();
    StitchWriter.write(stitch, out.resolve("LL-stitch.dcm"), (done, total) -> true);
    System.out.printf(
        Locale.ROOT,
        "  written %d x %d in %.1f s to %s%n",
        stitch.columns(),
        stitch.rows(),
        (System.nanoTime() - t) / 1e9,
        out.resolve("LL-stitch.dcm"));
  }

  private static void report(String what, Seam s, double seconds, MatchImage up) {
    System.out.printf(Locale.ROOT, "  -- %s (%.1f s): %s%n", what, seconds, s.summary());
    for (String r : s.reasons()) {
      System.out.println("     * " + r);
    }
    List<Rigid.Pair> pairs = s.pairs();
    if (pairs.isEmpty()) {
      return;
    }
    // where the agreeing points lie: thirds of the film's width, and their residual per third
    double w = up.widthMm();
    for (int k = 0; k < 3; k++) {
      final int third = k;
      List<Rigid.Pair> in =
          pairs.stream().filter(p -> (int) Math.min(2, p.tx() * 3 / w) == third).toList();
      if (in.isEmpty()) {
        continue;
      }
      double ymin = in.stream().mapToDouble(Rigid.Pair::ty).min().orElse(0);
      double ymax = in.stream().mapToDouble(Rigid.Pair::ty).max().orElse(0);
      Rigid own = in.size() >= 2 ? Rigid.fit(in) : null;
      System.out.printf(
          Locale.ROOT,
          "     third %d: %d points, rows %.0f..%.0f mm, residual rms %.2f mm%s%n",
          k + 1,
          in.size(),
          ymin,
          ymax,
          s.transform().rms(in),
          own == null
              ? ""
              : String.format(
                  Locale.ROOT,
                  "; fitted alone: rotation %.2f deg, differs from the seam by %.2f mm at its centre",
                  own.degrees(),
                  differ(s.transform(), own, in)));
    }
    List<Rigid.Pair> sorted = new ArrayList<>(pairs);
    sorted.sort(Comparator.comparingDouble(Rigid.Pair::tx));
    StringBuilder sb = new StringBuilder("     points (upper x,y mm: residual): ");
    for (Rigid.Pair p : sorted) {
      sb.append(
          String.format(Locale.ROOT, "%.0f,%.0f:%.1f ", p.tx(), p.ty(), s.transform().residual(p)));
    }
    System.out.println(sb);
  }

  private static double differ(Rigid a, Rigid b, List<Rigid.Pair> in) {
    double cx = in.stream().mapToDouble(Rigid.Pair::fx).average().orElse(0);
    double cy = in.stream().mapToDouble(Rigid.Pair::fy).average().orElse(0);
    double[] pa = a.apply(cx, cy);
    double[] pb = b.apply(cx, cy);
    return Math.hypot(pa[0] - pb[0], pa[1] - pb[1]);
  }

  /** The overlap as a checkerboard of the two pieces on the 1 mm grid, the seam's points marked. */
  private static void picture(Path file, MatchImage up, MatchImage down, Seam s)
      throws IOException {
    Gray u = up.fine();
    Gray l = down.fine();
    double[] box = SeamMatcher.overlap(up.widthMm(), up.heightMm(), s.transform(), down);
    int y0 = (int) Math.max(0, box[1] / u.mm() - 10);
    int h = u.height() - y0;
    int w = u.width();
    Rigid toLower = s.transform().inverse();
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    int cell = 10;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        double px = (x + 0.5) * u.mm();
        double py = (y0 + y + 0.5) * u.mm();
        float a = u.sample(px, py);
        double[] q = toLower.apply(px, py);
        float b = l.sample(q[0], q[1]);
        boolean useUpper = ((x / cell + y / cell) & 1) == 0;
        float v = useUpper ? a : b;
        if (Float.isNaN(v)) {
          v = useUpper ? b : a;
        }
        int g = Float.isNaN(v) ? 0 : (int) Math.max(0, Math.min(255, v * 255));
        img.setRGB(x, y, (g << 16) | (g << 8) | g);
      }
    }
    Graphics2D g = img.createGraphics();
    g.setColor(Color.GREEN);
    g.setStroke(new BasicStroke(1.5f));
    for (Rigid.Pair p : s.pairs()) {
      double x = p.tx() / u.mm();
      double y = p.ty() / u.mm() - y0;
      g.draw(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
    }
    g.dispose();
    ImageIO.write(img, "png", file.toFile());
  }

  private static Piece piece(Path dir, int instance) throws IOException {
    try (var files = Files.list(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".dcm")).toList()) {
        Attributes a;
        try (DicomInputStream in = new DicomInputStream(f.toFile())) {
          in.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
          a = in.readDataset();
        }
        if (a.getInt(Tag.InstanceNumber, -1) != instance) {
          continue;
        }
        int w = a.getInt(Tag.Columns, 0);
        int h = a.getInt(Tag.Rows, 0);
        return new Piece(a, w, h, () -> pixels(f, w * h));
      }
    }
    throw new IOException("no piece with InstanceNumber " + instance + " in " + dir);
  }

  // uncompressed 16-bit little-endian pixel data, with the modality LUT (slope 1, intercept 0)
  private static float[] pixels(Path f, int n) throws IOException {
    Attributes a;
    try (DicomInputStream in = new DicomInputStream(f.toFile())) {
      in.setIncludeBulkData(DicomInputStream.IncludeBulkData.YES);
      a = in.readDataset();
    }
    if (a.getInt(Tag.BitsAllocated, 16) != 16) {
      throw new IOException("the harness reads 16-bit pixels only");
    }
    byte[] bytes = a.getSafeBytes(Tag.PixelData);
    ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    boolean signed = a.getInt(Tag.PixelRepresentation, 0) == 1;
    double slope = a.getDouble(Tag.RescaleSlope, 1);
    double intercept = a.getDouble(Tag.RescaleIntercept, 0);
    float[] v = new float[n];
    for (int i = 0; i < n; i++) {
      short s = bb.getShort(2 * i);
      v[i] = (float) ((signed ? s : s & 0xffff) * slope + intercept);
    }
    return v;
  }
}
