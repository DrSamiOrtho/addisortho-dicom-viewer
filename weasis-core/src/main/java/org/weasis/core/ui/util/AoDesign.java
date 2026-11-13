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

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JViewport;
import javax.swing.SwingConstants;
import javax.swing.table.JTableHeader;
import javax.swing.text.JTextComponent;

/**
 * The Addis Ortho design tokens for code. The look and feel's own colours stay in
 * FlatWeasisTheme.properties. Lime is for small accents only, and never for text on white.
 */
public final class AoDesign {
  public static final Color GREEN = new Color(0x034537);
  public static final Color LIME = new Color(0xA8FF30);
  public static final Color GREEN_HOVER = new Color(0x075A48);
  public static final Color GREEN_PRESSED = new Color(0x02352B);
  public static final Color VIEW_RAIL = new Color(0x141414);
  public static final Color VIEW_ACTIVE = new Color(0x232323);
  public static final Color WORK_PAGE = new Color(0xF4F6F5);
  public static final Color WORK_CARD = Color.WHITE;
  public static final Color WORK_TEXT = new Color(0x14201C);
  public static final Color WORK_TEXT_SECONDARY = new Color(0x46544F);
  public static final Color WORK_OUTLINE = new Color(0x7A8A84);
  public static final Color WORK_HAIRLINE = new Color(0xD9E0DD);
  public static final Color WORK_SELECTION = new Color(0xE6F2ED);
  public static final Color WORK_HOVER = new Color(0xEDF1EF);

  /** A component whose font was chosen by the screen and must not be reset to the body font. */
  public static final String KEEP_FONT = "ao.keepFont"; // NON-NLS

  public static final int GAP_SMALL = 4;
  public static final int GAP = 8;
  public static final int GAP_LARGE = 16;

  private AoDesign() {}

  private static final String STYLE = FlatClientProperties.STYLE;
  private static final String LIGHT_BUTTON =
      "background:#FFFFFF; foreground:#14201C; borderColor:#7A8A84; hoverBackground:#EDF1EF;"
          + " pressedBackground:#D9E0DD; focusedBorderColor:#034537; focusColor:#034537;"
          + " focusedBackground:#FFFFFF; hoverBorderColor:#034537; disabledBackground:#F4F6F5;"
          + " disabledBorderColor:#D9E0DD; disabledText:#8E9A95; arc:12"; // NON-NLS
  private static final String PRIMARY_BUTTON =
      "background:#034537; foreground:#FFFFFF; borderColor:#034537; hoverBackground:#075A48;"
          + " pressedBackground:#02352B; focusedBorderColor:#A8FF30; focusColor:#034537;"
          + " focusedBackground:#034537; hoverBorderColor:#034537; disabledBackground:#EDF1EF;"
          + " disabledBorderColor:#D9E0DD; disabledText:#8E9A95; arc:12"; // NON-NLS
  private static final String LIGHT_FIELD =
      "background:#FFFFFF; foreground:#14201C; borderColor:#7A8A84; focusedBorderColor:#034537;"
          + " focusColor:#034537; placeholderForeground:#46544F; arc:12"; // NON-NLS
  private static final String LIGHT_SCROLLBAR =
      "track:#F4F6F5; thumb:#B4BFBA; hoverThumbColor:#7A8A84; pressedThumbColor:#46544F;"
          + " hoverTrackColor:#F4F6F5; pressedTrackColor:#F4F6F5"; // NON-NLS
  private static final String LIGHT_HEADER =
      "background:#FFFFFF; foreground:#46544F; hoverBackground:#EDF1EF;"
          + " pressedBackground:#D9E0DD; separatorColor:#D9E0DD;"
          + " bottomSeparatorColor:#7A8A84"; // NON-NLS
  private static final String LIGHT_MENU_ITEM =
      "background:#FFFFFF; foreground:#14201C; selectionBackground:#E6F2ED;"
          + " selectionForeground:#14201C; disabledForeground:#8E9A95"; // NON-NLS

  /**
   * Makes a screen a light work surface inside the dark application, from one set of rules. Only
   * what is under {@code root} changes: the look and feel stays dark, so the viewer does too.
   */
  public static void lightSurface(Component root) {
    if (root instanceof JComponent c
        && c.getClientProperty(KEEP_FONT) == null
        && !(root instanceof JPanel)) {
      c.setFont(AoFonts.regular(14));
    }
    switch (root) {
      case JButton b when b.getClientProperty(STYLE) == null ->
          b.putClientProperty(STYLE, LIGHT_BUTTON);
      case JTextField t -> {
        t.putClientProperty(STYLE, LIGHT_FIELD);
        t.setCaretColor(WORK_TEXT);
        t.setSelectionColor(WORK_SELECTION);
        t.setSelectedTextColor(WORK_TEXT);
      }
      case JTextComponent t -> {
        t.setForeground(WORK_TEXT);
        t.setCaretColor(WORK_TEXT);
        t.setSelectionColor(WORK_SELECTION);
        t.setSelectedTextColor(WORK_TEXT);
      }
      case JScrollBar b -> b.putClientProperty(STYLE, LIGHT_SCROLLBAR);
      case JTableHeader h -> h.putClientProperty(STYLE, LIGHT_HEADER);
      case JTable t -> {
        t.setBackground(WORK_CARD);
        t.setForeground(WORK_TEXT);
        t.setSelectionBackground(WORK_SELECTION);
        t.setSelectionForeground(WORK_TEXT);
        t.setGridColor(WORK_HAIRLINE);
      }
      case JLabel l when l.getForeground() != WORK_TEXT_SECONDARY -> l.setForeground(WORK_TEXT);
      case JViewport v -> v.setBackground(WORK_CARD);
      case JScrollPane s -> {
        s.setBackground(WORK_CARD);
        s.setBorder(BorderFactory.createLineBorder(WORK_HAIRLINE));
      }
      default -> {
        // Panels take their colour from the screen that lays them out.
      }
    }
    if (root instanceof Container c) {
      for (Component child : c.getComponents()) {
        lightSurface(child);
      }
    }
  }

  /** The one main action of a screen. */
  public static void primary(JButton button) {
    button.putClientProperty(STYLE, PRIMARY_BUTTON);
    button.setFont(AoFonts.semibold(14));
    button.putClientProperty(KEEP_FONT, Boolean.TRUE);
  }

  /** A menu that opens from a light screen. */
  public static void lightMenu(JPopupMenu menu) {
    menu.setBackground(WORK_CARD);
    menu.setBorder(BorderFactory.createLineBorder(WORK_OUTLINE));
    for (Component item : menu.getComponents()) {
      if (item instanceof JMenuItem c) {
        c.setFont(AoFonts.regular(14));
        c.putClientProperty(STYLE, LIGHT_MENU_ITEM);
      } else if (item instanceof JSeparator separator) {
        separator.setForeground(WORK_HAIRLINE);
      }
    }
  }

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
