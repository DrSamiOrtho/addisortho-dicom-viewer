/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;

/**
 * The Addis Ortho design tokens for code. The look and feel's own colours stay in
 * FlatWeasisTheme.properties. Lime is for small accents only, and never for text on white.
 */
public final class AoDesign {
  public static final Color GREEN = new Color(0x034537);
  public static final Color LIME = new Color(0xA8FF30);
  public static final int GAP_SMALL = 4;
  public static final int GAP = 8;
  public static final int GAP_LARGE = 16;

  private AoDesign() {}

  /** A thin strip of white text on the brand green, such as the open study's context. */
  public static JLabel strip() {
    JLabel label = new JLabel(" ", SwingConstants.LEADING);
    label.setOpaque(true);
    label.setBackground(GREEN);
    label.setForeground(Color.WHITE);
    label.setBorder(BorderFactory.createEmptyBorder(2, GAP, 2, GAP));
    return label;
  }

  /** The WCAG contrast ratio of two colours, from 1 to 21. */
  public static double contrast(Color a, Color b) {
    double la = luminance(a);
    double lb = luminance(b);
    return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
  }

  private static double luminance(Color c) {
    return 0.2126 * linear(c.getRed())
        + 0.7152 * linear(c.getGreen())
        + 0.0722 * linear(c.getBlue());
  }

  private static double linear(int channel) {
    double v = channel / 255.0;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  }
}
