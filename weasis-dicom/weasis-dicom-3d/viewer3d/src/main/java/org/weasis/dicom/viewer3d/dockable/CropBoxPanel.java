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
package org.weasis.dicom.viewer3d.dockable;

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.border.Border;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.Messages;
import org.weasis.dicom.viewer3d.vr.CropBox;
import org.weasis.dicom.viewer3d.vr.CropBox.Side;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Crop box of the selected 3D view: an on/off toggle, one slider per side giving the share of the
 * volume cut away there, and a reset. Registered as a {@link SeriesViewerListener}, it follows the
 * view selection.
 */
public class CropBoxPanel extends JPanel implements SeriesViewerListener {

  /** Slider resolution: 0.1 % of the volume per step. */
  private static final int SLIDER_MAX = 1000;

  private final JCheckBox enable = new JCheckBox(Messages.getString("enable"));
  private final JButton reset = new JButton(Messages.getString("crop.reset"));
  private final Map<Side, JSlider> sliders = new EnumMap<>(Side.class);
  private final Map<Side, JLabel> values = new EnumMap<>(Side.class);
  private boolean updating;

  public CropBoxPanel(Border outerBorder) {
    super(new GridBagLayout());
    setBorder(
        BorderFactory.createCompoundBorder(
            outerBorder, GuiUtils.getTitledBorder(Messages.getString("crop.box"))));

    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 3, 2, 3);
    c.anchor = GridBagConstraints.LINE_START;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 2;
    add(enable, c);
    c.gridx = 2;
    c.gridwidth = 1;
    c.anchor = GridBagConstraints.LINE_END;
    add(reset, c);

    for (Side side : Side.values()) {
      JSlider slider = new JSlider(0, SLIDER_MAX, 0);
      JLabel value = new JLabel(formatCut(0.0));
      sliders.put(side, slider);
      values.put(side, value);
      c.gridy = side.ordinal() + 1;
      c.gridx = 0;
      c.weightx = 0;
      c.fill = GridBagConstraints.NONE;
      c.anchor = GridBagConstraints.LINE_START;
      add(new JLabel(sideName(side)), c);
      c.gridx = 1;
      c.weightx = 1;
      c.fill = GridBagConstraints.HORIZONTAL;
      add(slider, c);
      c.gridx = 2;
      c.weightx = 0;
      c.fill = GridBagConstraints.NONE;
      c.anchor = GridBagConstraints.LINE_END;
      add(value, c);
      slider.addChangeListener(_ -> sliderChanged(side));
    }

    enable.addActionListener(
        _ -> {
          View3d view = selectedView3d();
          if (view != null) {
            view.setCropBox(enable.isSelected(), view.getCropBox(), false);
          }
          updateEnabled(view != null);
        });
    reset.addActionListener(
        _ -> {
          View3d view = selectedView3d();
          if (view != null) {
            view.setCropBox(enable.isSelected(), CropBox.FULL, false);
            refresh(view);
          }
        });
    refresh(null);
  }

  @Override
  public Dimension getMaximumSize() {
    // Keeps the box layout of the tool from stretching the panel vertically.
    return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
  }

  @Override
  public void changingViewContentEvent(SeriesViewerEvent event) {
    if (EVENT.SELECT_VIEW.equals(event.getEventType())
        && event.getSeriesViewer() instanceof ImageViewerPlugin<?> viewer) {
      refresh(viewer.getSelectedViewCanvas() instanceof View3d view3d ? view3d : null);
    }
  }

  private static View3d selectedView3d() {
    return EventManager.getInstance().getSelectedViewPane() instanceof View3d view3d
        ? view3d
        : null;
  }

  private void sliderChanged(Side side) {
    if (updating) {
      return;
    }
    View3d view = selectedView3d();
    if (view == null) {
      return;
    }
    JSlider slider = sliders.get(side);
    CropBox box = view.getCropBox().withCut(side, slider.getValue() / (double) SLIDER_MAX);
    view.setCropBox(enable.isSelected(), box, slider.getValueIsAdjusting());
    values.get(side).setText(formatCut(box.cut(side)));
    if (!slider.getValueIsAdjusting()) {
      refresh(view); // shows where the opposite side stopped this one
    }
  }

  /** Shows the crop box of {@code view}, or disables the controls when it is {@code null}. */
  private void refresh(View3d view) {
    updating = true;
    try {
      CropBox box = view == null ? CropBox.FULL : view.getCropBox();
      enable.setSelected(view != null && view.isCropEnabled());
      for (Side side : Side.values()) {
        double cut = box.cut(side);
        sliders.get(side).setValue((int) Math.round(cut * SLIDER_MAX));
        values.get(side).setText(formatCut(cut));
      }
      updateEnabled(view != null);
    } finally {
      updating = false;
    }
  }

  private void updateEnabled(boolean hasView) {
    enable.setEnabled(hasView);
    boolean active = hasView && enable.isSelected();
    reset.setEnabled(active);
    sliders.values().forEach(s -> s.setEnabled(active));
  }

  private static String formatCut(double cut) {
    return Math.round(cut * 100.0) + "%"; // NON-NLS
  }

  private static String sideName(Side side) {
    return switch (side) {
      case RIGHT -> Messages.getString("crop.right");
      case LEFT -> Messages.getString("crop.left");
      case FRONT -> Messages.getString("crop.front");
      case BACK -> Messages.getString("crop.back");
      case DOWN -> Messages.getString("crop.down");
      case UP -> Messages.getString("crop.up");
    };
  }
}
