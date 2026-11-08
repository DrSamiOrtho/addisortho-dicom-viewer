/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Builds the output pixels strip by strip (design sections 6 and 7): each output pixel is taken
 * from the piece the seams assign it to, feathered over 10 mm at each seam, with the grey mapping
 * applied; the draft label and the seam ticks are burned in. At most the pieces under the current
 * strip are held at full resolution.
 */
final class Compositor {

  static final int MAX_VALUE = 65000;
  static final int PADDING = 65535;
  private static final int STRIP = 128;
  private static final double TICK_MM = 15;
  private static final double LABEL_LINE_MM = 5;
  private static final double MARGIN_MM = 3;

  /** Reports progress; returns false to cancel. */
  @FunctionalInterface
  interface Progress {
    boolean rows(int done, int total);
  }

  private final Stitch stitch;
  private final List<Rigid> inverses;
  private final double[] seamY;
  private final double[] pieceTop;
  private final double[] pieceBottom;
  private final List<double[]> maps;
  final double mm;
  final int cols;
  final int rows;
  final double originX;
  final double originY;
  private final double lo;
  private final double gain;
  private final Overlay overlay;
  final double windowCenter;
  final double windowWidth;

  Compositor(Stitch stitch) {
    this.stitch = stitch;
    List<Rigid> poses = stitch.poses();
    this.inverses = poses.stream().map(Rigid::inverse).toList();
    int n = stitch.pieces.size();
    this.seamY = new double[n - 1];
    for (int i = 0; i < n - 1; i++) {
      seamY[i] = stitch.seamY(i);
    }
    this.pieceTop = new double[n];
    this.pieceBottom = new double[n];
    for (int i = 0; i < n; i++) {
      Piece p = stitch.pieces.get(i);
      pieceTop[i] = Double.POSITIVE_INFINITY;
      pieceBottom[i] = Double.NEGATIVE_INFINITY;
      for (double[] c :
          new double[][] {
            {0, 0}, {p.widthMm(), 0}, {0, p.heightMm()}, {p.widthMm(), p.heightMm()}
          }) {
        double y = poses.get(i).apply(c[0], c[1])[1];
        pieceTop[i] = Math.min(pieceTop[i], y);
        pieceBottom[i] = Math.max(pieceBottom[i], y);
      }
    }
    this.maps = stitch.exposure();
    this.mm = stitch.outputMm();
    this.cols = stitch.columns();
    // the label sits in its own strip above the image, so it never covers anatomy (Astra, P2)
    this.overlay = Overlay.place(stitch.labelLines(), mm, cols);
    this.rows = stitch.rows() + overlay.strip();
    double[] b = stitch.bounds();
    this.originX = b[0];
    this.originY = b[1] - overlay.strip() * mm;

    Gray preview = preview(MatchImage.COARSE_MM, false);
    float[] v = preview.finiteValues();
    Arrays.sort(v);
    double vlo = v.length == 0 ? 0 : v[(int) (v.length * 0.0002)];
    double vhi = v.length == 0 ? 1 : v[Math.min(v.length - 1, (int) (v.length * 0.9998))];
    double span = Math.max(vhi - vlo, 1e-6);
    this.lo = vlo;
    this.gain = span > MAX_VALUE ? MAX_VALUE / span : span < 256 ? 4095 / span : 1;
    double w1 = v.length == 0 ? 0 : stored(v[(int) (v.length * 0.01)]);
    double w99 =
        v.length == 0 ? MAX_VALUE : stored(v[Math.min(v.length - 1, (int) (v.length * 0.99))]);
    this.windowCenter = (w1 + w99) / 2;
    this.windowWidth = Math.max(1, w99 - w1);
  }

  int stored(double value) {
    return (int) Math.max(0, Math.min(MAX_VALUE, Math.round((value - lo) * gain)));
  }

  /**
   * The stitch on a coarse grid from the pieces' 2 mm copies: for the window's preview, the grey
   * range and the label's corner. NaN where no piece lies.
   */
  Gray preview(double gridMm, boolean withSeams) {
    double[] b = stitch.bounds();
    int w = Math.max(1, (int) Math.ceil((b[2] - b[0]) / gridMm));
    int h = Math.max(1, (int) Math.ceil((b[3] - b[1]) / gridMm));
    Sampler[] s = new Sampler[stitch.pieces.size()];
    for (int i = 0; i < s.length; i++) {
      Gray level = stitch.pieces.get(i).level();
      s[i] = level::sample;
    }
    float[] out = new float[w * h];
    double[] wt = new double[s.length];
    for (int y = 0; y < h; y++) {
      double gy = b[1] + (y + 0.5) * gridMm;
      for (int x = 0; x < w; x++) {
        out[y * w + x] = (float) value(b[0] + (x + 0.5) * gridMm, gy, s, wt);
      }
    }
    if (withSeams) {
      for (double sy : seamY) {
        int row = (int) ((sy - b[1]) / gridMm);
        if (row >= 0 && row < h) {
          for (int x = 0; x < w; x += 3) {
            out[row * w + x] = Float.NaN;
          }
        }
      }
    }
    return new Gray(w, h, gridMm, out);
  }

  /** Writes rows x cols unsigned 16-bit little-endian values, top row first. */
  void write(OutputStream out, Progress progress) throws IOException {
    int n = stitch.pieces.size();
    Map<Integer, Full> loaded = new HashMap<>();
    Sampler[] s = new Sampler[n];
    double[] wt = new double[n];
    byte[] buf = new byte[cols * 2 * STRIP];
    for (int r0 = 0; r0 < rows; r0 += STRIP) {
      int r1 = Math.min(rows, r0 + STRIP);
      double top = originY + r0 * mm;
      double bottom = originY + r1 * mm;
      for (int i = 0; i < n; i++) {
        boolean needed = pieceBottom[i] >= top && pieceTop[i] <= bottom;
        if (needed && !loaded.containsKey(i)) {
          Piece p = stitch.pieces.get(i);
          loaded.put(
              i, new Full(p.read(), p.width, p.height, p.spacing.colMm(), p.spacing.rowMm()));
        } else if (!needed) {
          loaded.remove(i); // its rows are done: free it before reading the next piece
        }
        Full f = loaded.get(i);
        s[i] = f == null ? null : f::sample;
      }
      int k = 0;
      for (int r = r0; r < r1; r++) {
        double gy = originY + (r + 0.5) * mm;
        for (int c = 0; c < cols; c++) {
          double v = value(originX + (c + 0.5) * mm, gy, s, wt);
          int px = overlay.apply(c, r, Double.isNaN(v) ? PADDING : stored(v));
          px = tick(c, r, px);
          buf[k++] = (byte) px;
          buf[k++] = (byte) (px >> 8);
        }
      }
      out.write(buf, 0, k);
      if (!progress.rows(r1, rows)) {
        throw new CancellationException();
      }
    }
  }

  /** A sample of piece i at a point in its own mm, NaN outside it. */
  @FunctionalInterface
  private interface Sampler {
    float at(double xMm, double yMm);
  }

  /** The mapped value at a stitch-frame point, or NaN where no piece lies. */
  private double value(double gx, double gy, Sampler[] s, double[] wt) {
    double sum = 0;
    double wsum = 0;
    for (int pass = 0; pass < 2 && wsum <= 0; pass++) {
      sum = 0;
      wsum = 0;
      for (int i = 0; i < s.length; i++) {
        if (s[i] == null) {
          continue;
        }
        double w = pass == 0 ? seamWeight(i, gy) : 1;
        if (w <= 0) {
          continue;
        }
        double[] p = inverses.get(i).apply(gx, gy);
        float v = s[i].at(p[0], p[1]);
        if (Float.isNaN(v)) {
          continue;
        }
        // a shutter or padding area yields to a piece that has real image there
        if (Float.isNaN(stitch.pieces.get(i).match().coarse().sample(p[0], p[1]))) {
          w *= 0.001;
        }
        double[] m = maps.get(i);
        sum += w * (m[0] * v + m[1]);
        wsum += w;
      }
    }
    return wsum > 0 ? sum / wsum : Double.NaN;
  }

  private double seamWeight(int i, double y) {
    double w = 1;
    if (i > 0) {
      w *= clamp((y - (seamY[i - 1] - Stitch.FEATHER_MM / 2)) / Stitch.FEATHER_MM);
    }
    if (i < seamY.length) {
      w *= clamp(((seamY[i] + Stitch.FEATHER_MM / 2) - y) / Stitch.FEATHER_MM);
    }
    return w;
  }

  private int tick(int c, int r, int px) {
    double x = (c + 0.5) * mm;
    if (x > TICK_MM && x < cols * mm - TICK_MM) {
      return px;
    }
    double y = originY + (r + 0.5) * mm;
    for (double sy : seamY) {
      if (Math.abs(y - sy) <= Math.max(0.4, mm)) {
        return MAX_VALUE;
      }
    }
    return px;
  }

  /** Output row of seam i, for the seam data. */
  int seamRow(int i) {
    return (int) Math.round((seamY[i] - originY) / mm);
  }

  private static double clamp(double v) {
    return Math.max(0, Math.min(1, v));
  }

  /** A piece at full resolution with its own, possibly non-square, pixel size. */
  private record Full(float[] d, int w, int h, double colMm, double rowMm) {
    float sample(double xMm, double yMm) {
      double fx = xMm / colMm - 0.5;
      double fy = yMm / rowMm - 0.5;
      if (fx < -0.5 || fy < -0.5 || fx > w - 0.5 || fy > h - 0.5) {
        return Float.NaN;
      }
      fx = Math.max(0, Math.min(w - 1.0001, fx));
      fy = Math.max(0, Math.min(h - 1.0001, fy));
      int x0 = (int) fx;
      int y0 = (int) fy;
      double ax = fx - x0;
      double ay = fy - y0;
      int i = y0 * w + x0;
      int x1 = x0 + 1 < w ? 1 : 0;
      int y1 = y0 + 1 < h ? w : 0;
      double t = d[i] * (1 - ax) + d[i + x1] * ax;
      double b = d[i + y1] * (1 - ax) + d[i + y1 + x1] * ax;
      return (float) (t * (1 - ay) + b * ay);
    }
  }

  /**
   * The burned-in draft label: white text on black in a strip of {@code strip} rows above the
   * image. The text is shrunk, then wrapped, so every word fits a narrow film (Astra, code review
   * P2).
   */
  record Overlay(int x, int y, int w, int h, int strip, byte[] alpha) {

    int apply(int c, int r, int px) {
      if (r < strip) {
        if (c < x || r < y || c >= x + w || r >= y + h) {
          return PADDING;
        }
        return (alpha[(r - y) * w + (c - x)] & 0xff) * MAX_VALUE / 255;
      }
      return px;
    }

    static Overlay place(List<String> lines, double mm, int cols) {
      int margin = Math.max(1, (int) Math.round(MARGIN_MM / mm));
      int pad = Math.max(1, (int) Math.round(2 / mm));
      int avail = Math.max(1, cols - 2 * margin - 2 * pad);
      int fontPx = Math.max(10, (int) Math.round(LABEL_LINE_MM / mm));
      int minPx = Math.max(10, (int) Math.round(2.5 / mm));
      BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D pg = probe.createGraphics();
      Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontPx);
      FontMetrics fm = pg.getFontMetrics(font);
      int widest = lines.stream().mapToInt(fm::stringWidth).max().orElse(1);
      if (widest > avail) {
        fontPx = Math.max(minPx, fontPx * avail / widest);
        font = font.deriveFont((float) fontPx);
        fm = pg.getFontMetrics(font);
      }
      List<String> wrapped = new ArrayList<>();
      for (String line : lines) {
        wrapped.addAll(wrap(line, fm, avail));
      }
      pg.dispose();
      int w = Math.min(cols, wrapped.stream().mapToInt(fm::stringWidth).max().orElse(1) + 2 * pad);
      int h = wrapped.size() * fm.getHeight() + 2 * pad;
      BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
      Graphics2D g = img.createGraphics();
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g.setColor(Color.BLACK);
      g.fillRect(0, 0, w, h);
      g.setColor(Color.WHITE);
      g.setFont(font);
      int baseline = pad + fm.getAscent();
      for (String line : wrapped) {
        g.drawString(line, pad, baseline);
        baseline += fm.getHeight();
      }
      g.dispose();
      byte[] alpha = new byte[w * h];
      img.getRaster().getDataElements(0, 0, w, h, alpha);
      int x = Math.max(0, Math.min(margin, cols - w));
      return new Overlay(x, margin, w, h, h + 2 * margin, alpha);
    }

    // greedy word wrap; a single word wider than the line is kept whole
    static List<String> wrap(String line, FontMetrics fm, int avail) {
      List<String> out = new ArrayList<>();
      StringBuilder cur = new StringBuilder();
      for (String word : line.split(" ")) {
        String next = cur.isEmpty() ? word : cur + " " + word;
        if (fm.stringWidth(next) > avail && !cur.isEmpty()) {
          out.add(cur.toString());
          cur = new StringBuilder(word);
        } else {
          cur = new StringBuilder(next);
        }
      }
      if (!cur.isEmpty()) {
        out.add(cur.toString());
      }
      return out;
    }
  }
}
