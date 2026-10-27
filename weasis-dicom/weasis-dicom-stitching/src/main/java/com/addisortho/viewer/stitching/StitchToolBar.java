/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.Component;
import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.util.WtoolBar;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer2d.EventManager;

/** The Stitch button: opens the stitch window for the selected image's study. */
public class StitchToolBar extends WtoolBar {

  StitchToolBar() {
    super(Messages.getString("StitchToolBar.title"), 13);
    JButton button =
        new JButton(
            new FlatSVGIcon(
                    Objects.requireNonNull(StitchToolBar.class.getResource("/icon/svg/stitch.svg")))
                .derive(ResourceUtil.TOOLBAR_ICON_SIZE, ResourceUtil.TOOLBAR_ICON_SIZE));
    button.setToolTipText(Messages.getString("StitchToolBar.tooltip"));
    button.getAccessibleContext().setAccessibleName(Messages.getString("StitchToolBar.stitch"));
    button.addActionListener(e -> open(button));
    add(button);
  }

  private static void open(Component parent) {
    ViewCanvas<DicomImageElement> view = EventManager.getInstance().getSelectedViewPane();
    MediaSeries<DicomImageElement> series = view == null ? null : view.getSeries();
    StitchService.Study study = StitchService.study(series);
    if (study == null) {
      tell(parent, Messages.getString("StitchToolBar.noImage"));
      return;
    }
    if (study.candidates().size() < 2) {
      tell(parent, Messages.getString("StitchToolBar.tooFew").formatted(study.candidates().size()));
      return;
    }
    new StitchDialog(SwingUtilities.getWindowAncestor(parent), study).setVisible(true);
  }

  private static void tell(Component parent, String message) {
    JOptionPane.showMessageDialog(
        parent,
        message,
        Messages.getString("StitchToolBar.title"),
        JOptionPane.INFORMATION_MESSAGE);
  }
}
