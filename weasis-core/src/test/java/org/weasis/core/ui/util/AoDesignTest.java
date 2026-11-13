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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.Color;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AoDesignTest {
  private static final double READABLE = 4.5;

  @Test
  void theBrandPairsAreReadable() {
    assertTrue(AoDesign.contrast(AoDesign.GREEN, Color.WHITE) >= READABLE);
    assertTrue(AoDesign.contrast(AoDesign.LIME, Color.BLACK) >= READABLE);
    assertTrue(AoDesign.contrast(AoDesign.LIME, AoDesign.GREEN) >= READABLE);
  }

  @Test
  void limeOnWhiteIsNotReadableWhichIsWhyItIsNeverUsed() {
    assertTrue(AoDesign.contrast(AoDesign.LIME, Color.WHITE) < READABLE);
  }

  @Test
  void blackOnWhiteIsTheMaximum() {
    assertEquals(21.0, AoDesign.contrast(Color.BLACK, Color.WHITE), 0.01);
  }

  /** A misspelt style is only logged by FlatLaf, and the screen silently stays dark. */
  @Test
  void everyLightSurfaceStyleIsUnderstoodByTheLookAndFeel() {
    FlatDarkLaf.setup();
    Logger logger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> problems = new ListAppender<>();
    problems.start();
    logger.addAppender(problems);
    try {
      JPanel root = new JPanel();
      JButton primary = new JButton("a");
      AoDesign.primary(primary);
      JTable table = new JTable();
      root.add(primary);
      root.add(new JButton("b"));
      root.add(new JTextField());
      root.add(new JTextArea());
      root.add(new JLabel("c"));
      root.add(new JScrollPane(table));
      root.add(table.getTableHeader());
      JScrollBar bar = new JScrollBar();
      root.add(bar);
      AoDesign.lightSurface(root);
      JPopupMenu menu = new JPopupMenu();
      menu.add(new JMenuItem("d"));
      menu.addSeparator();
      AoDesign.lightMenu(menu);

      assertEquals(
          List.of(), problems.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
      assertEquals(AoDesign.GREEN, primary.getBackground());
    } finally {
      logger.detachAppender(problems);
    }
  }
}
