/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.Component;
import java.net.URI;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.util.WtoolBar;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer2d.EventManager;

/** The Template button: opens the selected X-ray in the templating page, offline. */
public class TemplatingToolBar extends WtoolBar {

  private static final Logger LOGGER = LoggerFactory.getLogger(TemplatingToolBar.class);

  TemplatingToolBar() {
    super(Messages.getString("TemplatingToolBar.title"), 12);
    JButton button =
        new JButton(
            new FlatSVGIcon(
                    Objects.requireNonNull(
                        TemplatingToolBar.class.getResource("/icon/svg/templating.svg")))
                .derive(ResourceUtil.TOOLBAR_ICON_SIZE, ResourceUtil.TOOLBAR_ICON_SIZE));
    button.setToolTipText(Messages.getString("TemplatingToolBar.tooltip"));
    button.getAccessibleContext().setAccessibleName(Messages.getString("TemplatingToolBar.template"));
    button.addActionListener(e -> template(button));
    add(button);
  }

  private static void template(Component parent) {
    ViewCanvas<DicomImageElement> view = EventManager.getInstance().getSelectedViewPane();
    MediaSeries<DicomImageElement> series = view == null ? null : view.getSeries();
    DicomImageElement image = view == null ? null : view.getImage();
    String why = TemplatingService.refusal(series, image);
    if (why != null) {
      JOptionPane.showMessageDialog(
          parent, why, Messages.getString("TemplatingToolBar.title"), JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    new SwingWorker<URI, Void>() {
      @Override
      protected URI doInBackground() throws Exception {
        return TemplatingService.handOver(series, image);
      }

      @Override
      protected void done() {
        try {
          URI page = get();
          // the link in the log too, as local servers do: it reaches the page if the browser does not open
          LOGGER.info("Templating page for this X-ray: {}", page);
          GuiUtils.openInDefaultBrowser(parent, page);
        } catch (Exception e) {
          Throwable cause = e.getCause() == null ? e : e.getCause();
          LOGGER.error("Cannot open the templating page", cause);
          JOptionPane.showMessageDialog(
              parent,
              Messages.getString("TemplatingToolBar.failed").formatted(cause.getMessage()),
              Messages.getString("TemplatingToolBar.title"),
              JOptionPane.ERROR_MESSAGE);
        }
      }
    }.execute();
  }
}
