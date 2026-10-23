/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.base.ui.gui;

import bibliothek.gui.dock.common.CWorkingArea;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint.CycleMethod;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;
import org.weasis.base.ui.Messages;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.LogoIcon;

/**
 * Paints the Addis Ortho start screen over the main working area while it holds no viewer. It only
 * paints: mouse events and drops still reach the working area underneath.
 */
class WelcomeLayerUI extends LayerUI<JComponent> {
  private static final Color BRAND_GREEN = new Color(0x03, 0x45, 0x37);
  private static final Color ACCENT = new Color(0xA8, 0xFF, 0x30);

  private final CWorkingArea mainArea;
  private FlatSVGIcon wordmark;
  private int wordmarkWidth;

  WelcomeLayerUI(CWorkingArea mainArea) {
    this.mainArea = mainArea;
  }

  @Override
  public void paint(Graphics g, JComponent c) {
    super.paint(g, c);
    if (mainArea.getStation().getDockableCount() > 0) {
      return;
    }
    Component area = mainArea.getComponent();
    if (!area.isShowing()) {
      return;
    }
    Rectangle r = SwingUtilities.convertRectangle(area.getParent(), area.getBounds(), c);
    if (r.width < UIScale.scale(320) || r.height < UIScale.scale(260)) {
      return;
    }
    Graphics2D g2 = (Graphics2D) g.create();
    try {
      g2.clipRect(r.x, r.y, r.width, r.height);
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g2.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      paintWelcome(g2, r);
    } finally {
      g2.dispose();
    }
  }

  private void paintWelcome(Graphics2D g2, Rectangle r) {
    int cx = r.x + r.width / 2;
    FlatSVGIcon logo = getWordmark(Math.min(420, UIScale.unscale(r.width * 2 / 3)));
    int logoW = logo.getIconWidth();
    int logoH = logo.getIconHeight();
    int titleGap = UIScale.scale(18);
    int lineGap = UIScale.scale(10);

    Font base = UIManager.getFont("Label.font");
    Font title = base.deriveFont(Font.PLAIN, base.getSize2D() * 1.75f);
    Font body = base.deriveFont(base.getSize2D() * 1.05f);
    FontMetrics fmTitle = g2.getFontMetrics(title);
    FontMetrics fmBody = g2.getFontMetrics(body);

    int blockH =
        logoH
            + titleGap
            + fmTitle.getHeight()
            + lineGap
            + UIScale.scale(2)
            + lineGap * 2
            + fmBody.getHeight() * 2;
    int y = r.y + Math.max(UIScale.scale(24), (r.height - blockH) / 2 - UIScale.scale(20));

    // Soft brand-green glow behind the wordmark
    float radius = Math.max(logoW * 1.1f, 1f);
    g2.setPaint(
        new RadialGradientPaint(
            cx,
            y + logoH / 2f,
            radius,
            new float[] {0f, 1f},
            new Color[] {
              new Color(BRAND_GREEN.getRed(), BRAND_GREEN.getGreen(), BRAND_GREEN.getBlue(), 110),
              new Color(BRAND_GREEN.getRed(), BRAND_GREEN.getGreen(), BRAND_GREEN.getBlue(), 0)
            },
            CycleMethod.NO_CYCLE));
    g2.fillOval(
        (int) (cx - radius), (int) (y + logoH / 2f - radius), (int) radius * 2, (int) radius * 2);

    logo.paintIcon(null, g2, cx - logoW / 2, y);
    y += logoH + titleGap;

    Color fg = UIManager.getColor("Label.foreground");
    Color secondary = UIManager.getColor("Component.infoForeground");
    if (secondary == null) {
      secondary = UIManager.getColor("Label.disabledForeground");
    }

    g2.setFont(title);
    g2.setColor(fg);
    drawCentered(g2, Messages.getString("Welcome.title"), cx, y + fmTitle.getAscent());
    y += fmTitle.getHeight() + lineGap;

    // Small accent rule, the only lime besides the wordmark
    int ruleW = UIScale.scale(36);
    g2.setColor(ACCENT);
    g2.fillRoundRect(cx - ruleW / 2, y, ruleW, UIScale.scale(2), 2, 2);
    y += UIScale.scale(2) + lineGap * 2;

    g2.setFont(body);
    g2.setColor(secondary);
    drawCentered(g2, Messages.getString("Welcome.tagline"), cx, y + fmBody.getAscent());
    y += fmBody.getHeight();
    drawCentered(g2, Messages.getString("Welcome.hint"), cx, y + fmBody.getAscent());
  }

  /** The wordmark at {@code width} unscaled pixels (the SVG is 448 x 162). */
  private FlatSVGIcon getWordmark(int width) {
    if (wordmark == null || wordmarkWidth != width) {
      wordmark = ResourceUtil.getIcon(LogoIcon.WORDMARK).derive(width, width * 162 / 448);
      wordmarkWidth = width;
    }
    return wordmark;
  }

  private static void drawCentered(Graphics2D g2, String text, int cx, int baseline) {
    int w = g2.getFontMetrics().stringWidth(text);
    g2.drawString(text, cx - w / 2, baseline);
  }
}
