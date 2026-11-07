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
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;
import java.util.function.IntConsumer;
import javax.swing.JPanel;

/**
 * Shows a picture scaled to fit, keeps its aspect, and reports clicks, drags and wheel turns in
 * picture pixels.
 */
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
  private BiConsumer<Double, Double> onDrag;
  private IntConsumer onWheel;
  private int lastX;
  private int lastY;

  ImagePanel(BiConsumer<Double, Double> onClick) {
    setBackground(Color.BLACK);
    setPreferredSize(new Dimension(360, 240));
    MouseAdapter mouse =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            lastX = e.getX();
            lastY = e.getY();
            if (image != null && onClick != null) {
              onClick.accept((e.getX() - ox) / scale, (e.getY() - oy) / scale);
            }
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            if (image != null && onDrag != null) {
              onDrag.accept((e.getX() - lastX) / scale, (e.getY() - lastY) / scale);
            }
            lastX = e.getX();
            lastY = e.getY();
          }

          @Override
          public void mouseWheelMoved(MouseWheelEvent e) {
            if (image != null && onWheel != null && e.getWheelRotation() != 0) {
              onWheel.accept(e.getWheelRotation());
            }
          }
        };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
    addMouseWheelListener(mouse);
  }

  /** Called while the mouse drags over the picture, with the move in picture pixels. */
  void setOnDrag(BiConsumer<Double, Double> onDrag) {
    this.onDrag = onDrag;
  }

  /** Called for each wheel notch, with the notches turned (negative towards the user). */
  void setOnWheel(IntConsumer onWheel) {
    this.onWheel = onWheel;
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

  /** A grey picture from values in 0..1, black where there is no picture (NaN), as saved. */
  static BufferedImage gray(float[] v, int w, int h) {
    return gray(v, w, h, 0x000000);
  }

  /**
   * As {@link #gray(float[], int, int)}, with NaN dark blue: for the seam editor's overlap, where
   * "no data" and "no difference" must be told apart.
   */
  static BufferedImage grayMarked(float[] v, int w, int h) {
    return gray(v, w, h, 0x101830);
  }

  private static BufferedImage gray(float[] v, int w, int h, int nanRgb) {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    for (int i = 0; i < v.length; i++) {
      float f = v[i];
      int rgb;
      if (Float.isNaN(f)) {
        rgb = nanRgb;
      } else {
        int g = (int) Math.max(0, Math.min(255, f * 255));
        rgb = (g << 16) | (g << 8) | g;
      }
      img.setRGB(i % w, i / w, rgb);
    }
    return img;
  }
}
