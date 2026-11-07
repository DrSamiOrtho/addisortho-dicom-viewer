/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.base.ui.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.weasis.base.ui.Messages;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.LogoIcon;
import org.weasis.core.ui.editor.SeriesViewerUI;
import org.weasis.core.ui.editor.image.ViewerPlugin;

/**
 * The Addis Ortho mark at the left of the toolbar, as tall as the toolbar buttons. Clicking it goes
 * back to the start screen: after a confirmation it closes every open viewer, as "Close All" does.
 */
class BrandMark extends JButton {
  private final JComponent buttonRow;
  private FlatSVGIcon icon;
  private int iconSize;

  BrandMark(JComponent buttonRow) {
    this.buttonRow = buttonRow;
    putClientProperty(
        FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    setFocusable(false);
    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    setToolTipText(Messages.getString("BrandMark.home"));
    addActionListener(_ -> goHome());
  }

  @Override
  public Dimension getPreferredSize() {
    int h = Math.max(buttonRow.getPreferredSize().height, UIScale.scale(24));
    return new Dimension(h + UIScale.scale(6), h);
  }

  @Override
  public Dimension getMinimumSize() {
    return getPreferredSize();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    int size = Math.min(getWidth(), getHeight()) - 2 * UIScale.scale(4);
    FlatSVGIcon mark = getIcon(UIScale.unscale(size));
    mark.paintIcon(
        this, g, (getWidth() - mark.getIconWidth()) / 2, (getHeight() - mark.getIconHeight()) / 2);
  }

  /** The mark at {@code size} unscaled pixels. */
  private FlatSVGIcon getIcon(int size) {
    if (icon == null || iconSize != size) {
      icon = ResourceUtil.getIcon(LogoIcon.SMALL, size, size);
      iconSize = size;
    }
    return icon;
  }

  private void goHome() {
    List<ViewerPlugin<?>> viewers = new ArrayList<>(GuiUtils.getUICore().getViewerPlugins());
    viewers.removeIf(v -> !v.getDockable().isCloseable());
    if (viewers.isEmpty()) {
      return;
    }
    int answer =
        JOptionPane.showConfirmDialog(
            SwingUtilities.getWindowAncestor(this),
            Messages.getString("BrandMark.confirm"),
            Messages.getString("BrandMark.home"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE);
    if (answer != JOptionPane.OK_OPTION) {
      return;
    }
    for (ViewerPlugin<?> viewer : viewers) {
      SeriesViewerUI.updateTools(viewer, null, true);
      viewer.close();
      viewer.handleFocusAfterClosing();
    }
  }
}
