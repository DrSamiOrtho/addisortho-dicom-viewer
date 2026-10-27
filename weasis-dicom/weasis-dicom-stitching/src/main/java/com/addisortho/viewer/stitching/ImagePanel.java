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
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;
import javax.swing.JPanel;

/** Shows a picture scaled to fit, keeps its aspect, and reports clicks in picture pixels. */
final class ImagePanel extends JPanel {

  /** Paints over the picture; {@code scale} is screen pixels per picture pixel. */
  @FunctionalInterface
  interface Overlay {
    void paint(Graphics2D g, double scale);
  }

  private BufferedImage image;
  private Overlay overlay;
  private double scale = 1;
  private int ox;
  private int oy;

  ImagePanel(BiConsumer<Double, Double> onClick) {
    setBackground(Color.BLACK);
    setPreferredSize(new Dimension(360, 240));
    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            if (image != null && onClick != null) {
              onClick.accept((e.getX() - ox) / scale, (e.getY() - oy) / scale);
            }
          }
        });
  }

  void setImage(BufferedImage image, Overlay overlay) {
    this.image = image;
    this.overlay = overlay;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    if (image == null) {
      return;
    }
    scale =
        Math.min((double) getWidth() / image.getWidth(), (double) getHeight() / image.getHeight());
    int w = (int) Math.round(image.getWidth() * scale);
    int h = (int) Math.round(image.getHeight() * scale);
    ox = (getWidth() - w) / 2;
    oy = (getHeight() - h) / 2;
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g2.drawImage(image, ox, oy, w, h, null);
    if (overlay != null) {
      g2.translate(ox, oy);
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      overlay.paint(g2, scale);
    }
    g2.dispose();
  }

  /** A grey picture from values in 0..1 (NaN dark blue: masked or outside the piece). */
  static BufferedImage gray(float[] v, int w, int h) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int i = 0; i < v.length; i++) {
      float f = v[i];
      int rgb;
      if (Float.isNaN(f)) {
        rgb = 0x101830;
      } else {
        int g = (int) Math.max(0, Math.min(255, f * 255));
        rgb = (g << 16) | (g << 8) | g;
      }
      img.setRGB(i % w, i / w, rgb);
    }
    return img;
  }
}
