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

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import org.weasis.core.Messages;

/**
 * One window with its places: the rail on the left, and beside it the studies home or the viewer.
 * The viewer is not touched by being hidden, so going back to it finds its layout as it was.
 */
public final class AppShell {
  /** A place of the application. */
  public enum Place {
    STUDIES,
    REVIEW
  }

  private static final AppShell INSTANCE = new AppShell();
  private static final int RAIL_WIDTH = 72;
  private static final int RAIL_ITEM_HEIGHT = 64;
  private static final int ICON = 20;

  private final CardLayout cardLayout = new CardLayout();
  private final JPanel cards = new JPanel(cardLayout);
  private final JPanel rail = new JPanel();
  private final JPanel root = new JPanel(new BorderLayout());
  private final RailButton studies =
      new RailButton(Messages.getString("shell.studies"), "ao/icons/search.svg"); // NON-NLS
  private final RailButton review =
      new RailButton(Messages.getString("shell.review"), "ao/icons/review.svg"); // NON-NLS
  private Component home;
  private Place current = Place.REVIEW;
  private boolean reviewAsked;
  private Runnable returnToViewer = () -> {};

  AppShell() {
    rail.setLayout(new BoxLayout(rail, BoxLayout.Y_AXIS));
    rail.setPreferredSize(new Dimension(UIScale.scale(RAIL_WIDTH), 0));
    ButtonGroup group = new ButtonGroup();
    group.add(studies);
    group.add(review);
    studies.addActionListener(_ -> show(Place.STUDIES));
    review.addActionListener(
        _ -> {
          show(Place.REVIEW);
          returnToViewer.run();
        });
    studies.setEnabled(false);
    rail.add(Box.createVerticalStrut(UIScale.scale(AoDesign.GAP_SMALL)));
    rail.add(studies);
    rail.add(review);
    rail.add(Box.createVerticalGlue());
    root.add(rail, BorderLayout.WEST);
    root.add(cards, BorderLayout.CENTER);
    paintPlace();
  }

  public static AppShell getInstance() {
    return INSTANCE;
  }

  /**
   * @param viewer the toolbar and the docking area
   * @return what fills the window
   */
  public JComponent build(JComponent viewer) {
    cards.add(viewer, Place.REVIEW.name());
    show(home != null && !reviewAsked ? Place.STUDIES : Place.REVIEW);
    return root;
  }

  /** The studies home. Starts there unless something has already opened in the viewer. */
  public void setHome(Component homeScreen) {
    home = homeScreen;
    cards.add(homeScreen, Place.STUDIES.name());
    studies.setEnabled(true);
    if (!reviewAsked) {
      show(Place.STUDIES);
    }
  }

  /** What gives the keyboard back to the viewer when the user returns to it from the rail. */
  public void onReturnToViewer(Runnable action) {
    returnToViewer = action;
  }

  public Place current() {
    return current;
  }

  public void show(Place place) {
    if (place == Place.STUDIES && home == null) {
      return;
    }
    reviewAsked |= place == Place.REVIEW && root.isShowing();
    current = place;
    cardLayout.show(cards, place.name());
    (place == Place.STUDIES ? studies : review).setSelected(true);
    paintPlace();
    if (place == Place.STUDIES) {
      home.requestFocusInWindow();
    }
  }

  /** Something was opened in the viewer, whichever way it got there. */
  public void showViewer() {
    reviewAsked = true;
    if (current != Place.REVIEW) {
      show(Place.REVIEW);
    }
  }

  /** Brand green around work; neutral grey around the images, so nothing tints how they look. */
  private void paintPlace() {
    rail.setBackground(current == Place.REVIEW ? AoDesign.VIEW_RAIL : AoDesign.GREEN);
    studies.repaint();
    review.repaint();
  }

  private final class RailButton extends JToggleButton {
    private final FlatSVGIcon icon;

    RailButton(String text, String iconPath) {
      super(text);
      icon = new FlatSVGIcon(iconPath, ICON, ICON, AoDesign.class.getClassLoader());
      setIcon(tinted(Color.WHITE));
      setSelectedIcon(tinted(AoDesign.LIME));
      setDisabledIcon(tinted(AoDesign.WORK_OUTLINE));
      setVerticalTextPosition(SwingConstants.BOTTOM);
      setHorizontalTextPosition(SwingConstants.CENTER);
      setAlignmentX(CENTER_ALIGNMENT);
      setBorder(BorderFactory.createEmptyBorder());
      setFocusPainted(false);
      setContentAreaFilled(false);
      setOpaque(false);
      setForeground(Color.WHITE);
      setFont(AoFonts.semibold(12));
      Dimension size = new Dimension(UIScale.scale(RAIL_WIDTH), UIScale.scale(RAIL_ITEM_HEIGHT));
      setPreferredSize(size);
      setMinimumSize(size);
      setMaximumSize(size);
      addChangeListener(_ -> setForeground(isSelected() ? AoDesign.LIME : Color.WHITE));
    }

    private FlatSVGIcon tinted(Color color) {
      FlatSVGIcon copy = icon.derive(ICON, ICON);
      copy.setColorFilter(new FlatSVGIcon.ColorFilter(_ -> color));
      return copy;
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      if (isSelected()) {
        g2.setColor(current == Place.REVIEW ? AoDesign.VIEW_ACTIVE : AoDesign.GREEN_PRESSED);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(AoDesign.LIME);
        int inset = UIScale.scale(AoDesign.GAP);
        g2.fillRect(0, inset, UIScale.scale(3), getHeight() - 2 * inset);
      }
      if (isFocusOwner()) {
        int width = UIScale.scale(2);
        Insets in = new Insets(width, width, width, width);
        g2.setColor(AoDesign.LIME);
        g2.setStroke(new java.awt.BasicStroke(width));
        g2.drawRoundRect(
            in.left, in.top, getWidth() - 2 * in.left - 1, getHeight() - 2 * in.top - 1, 6, 6);
      }
      g2.dispose();
      super.paintComponent(g);
    }
  }
}
