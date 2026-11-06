/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import java.awt.BorderLayout;
import java.awt.Point;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.Timer;
import javax.swing.UIManager;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;

/**
 * A small notice in the corner of the application window. It takes no focus and blocks nothing, so
 * it can appear while the doctor is working.
 */
final class ReceiveToast {

  private static final int VISIBLE_MS = 30_000;
  private static final int RETRY_MS = 2_000;
  private static final int MARGIN = 16;
  private static final int MAX_TEXT_WIDTH = 420;

  private static JWindow window; // Used on the event thread only.
  private static Timer hideTimer;

  private ReceiveToast() {}

  /** A message with no action, shown as soon as the application window exists. */
  static void showMessage(String message) {
    GuiExecutor.execute(() -> show(message, null));
  }

  static void showArrival(String message, Runnable showAction) {
    GuiExecutor.execute(() -> show(message, showAction));
  }

  private static void show(String message, Runnable showAction) {
    Window owner = GuiUtils.getUICore().getApplicationWindow();
    if (owner == null || !owner.isShowing()) {
      Timer retry = new Timer(RETRY_MS, e -> show(message, showAction));
      retry.setRepeats(false);
      retry.start();
      return;
    }
    close();
    window = new JWindow(owner);
    window.setFocusableWindowState(false);

    JPanel panel = new JPanel(new BorderLayout(MARGIN, 0));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)));
    JLabel text = new JLabel(toHtml(message));
    panel.add(text, BorderLayout.CENTER);

    JPanel buttons = new JPanel();
    if (showAction != null) {
      JButton show = new JButton(Messages.getString("receive.show"));
      show.addActionListener(
          e -> {
            close();
            showAction.run();
          });
      buttons.add(show);
    }
    JButton dismiss = new JButton(Messages.getString("receive.toast.close"));
    dismiss.addActionListener(e -> close());
    buttons.add(dismiss);
    panel.add(buttons, BorderLayout.EAST);

    window.setContentPane(panel);
    window.pack();
    Point corner = owner.getLocationOnScreen();
    window.setLocation(
        corner.x + owner.getWidth() - window.getWidth() - MARGIN,
        corner.y + owner.getHeight() - window.getHeight() - MARGIN);
    window.setVisible(true);

    hideTimer = new Timer(VISIBLE_MS, e -> close());
    hideTimer.setRepeats(false);
    hideTimer.start();
  }

  private static void close() {
    if (hideTimer != null) {
      hideTimer.stop();
      hideTimer = null;
    }
    if (window != null) {
      window.dispose();
      window = null;
    }
  }

  /** Patient names are untrusted, so they must not be read as HTML by the label. */
  private static String toHtml(String message) {
    String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return "<html><div style='width:" + MAX_TEXT_WIDTH + "px'>" + safe + "</div></html>";
  }
}
