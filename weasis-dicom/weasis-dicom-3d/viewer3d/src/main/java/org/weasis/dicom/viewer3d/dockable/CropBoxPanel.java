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
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.border.Border;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.core.ui.editor.image.MouseActions;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.Messages;
import org.weasis.dicom.viewer3d.vr.CropBox;
import org.weasis.dicom.viewer3d.vr.CropBox.Side;
import org.weasis.dicom.viewer3d.vr.LassoCut;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Crop of the selected 3D view, as RadiAnt offers it:
 *
 * <ul>
 *   <li>the box: an on/off toggle, one slider per side giving the share of the volume cut away
 *       there, a reset, and Fit to bone, which fits the box to the bone as the 3D view does when a
 *       CT opens;
 *   <li>free-hand: the scalpel draws an area on the view, then Keep inside or Remove inside cuts
 *       through the whole depth;
 *   <li>Undo and Redo walk through the box changes and the cuts together, many steps deep, and
 *       Whole volume shows everything again (itself undoable).
 * </ul>
 *
 * Registered as a {@link SeriesViewerListener}, it follows the view selection, and it listens to
 * the tool changes of the 3D event manager to show undo, redo and auto-crop changes.
 */
public class CropBoxPanel extends JPanel implements SeriesViewerListener {

  /** Slider resolution: 0.1 % of the volume per step. */
  private static final int SLIDER_MAX = 1000;

  private final JCheckBox enable = new JCheckBox(Messages.getString("enable"));
  private final JButton reset = new JButton(Messages.getString("crop.reset"));
  private final JButton fitBone = new JButton(Messages.getString("crop.fitBone"));
  private final JToggleButton freeHand = new JToggleButton(Messages.getString("crop.freehand"));
  private final JButton keepInside = new JButton(Messages.getString("crop.keepInside"));
  private final JButton removeInside = new JButton(Messages.getString("crop.removeInside"));
  private final JButton undo = new JButton(Messages.getString("crop.undo"));
  private final JButton redo = new JButton(Messages.getString("crop.redo"));
  private final JButton whole = new JButton(Messages.getString("crop.whole"));
  private final JLabel hint = new JLabel(Messages.getString("crop.hint"));
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

    c.gridy = Side.values().length + 1;
    c.gridx = 0;
    c.gridwidth = 3;
    c.weightx = 1;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.LINE_START;
    add(GuiUtils.getFlowLayoutPanel(fitBone), c);
    c.gridy++;
    add(GuiUtils.getFlowLayoutPanel(freeHand, keepInside, removeInside), c);
    c.gridy++;
    add(GuiUtils.getFlowLayoutPanel(undo, redo, whole), c);
    c.gridy++;
    add(hint, c);

    fitBone.setToolTipText(Messages.getString("crop.fitBone.tip"));
    freeHand.setToolTipText(Messages.getString("crop.freehand.tip"));
    keepInside.setToolTipText(Messages.getString("crop.keepInside.tip"));
    removeInside.setToolTipText(Messages.getString("crop.removeInside.tip"));
    undo.setToolTipText(Messages.getString("cut.undo"));
    redo.setToolTipText(Messages.getString("cut.redo"));
    whole.setToolTipText(Messages.getString("cut.restore"));
    fitBone.addActionListener(_ -> withView(View3d::autoCropToBone));
    freeHand.addActionListener(
        _ ->
            EventManager.getInstance()
                .changeLeftMouseAction(
                    freeHand.isSelected() ? ActionVol.SCALPEL.cmd() : ActionW.ROTATION.cmd()));
    keepInside.addActionListener(_ -> withView(v -> v.cutLasso(LassoCut.Side.OUTSIDE)));
    removeInside.addActionListener(_ -> withView(v -> v.cutLasso(LassoCut.Side.INSIDE)));
    undo.addActionListener(_ -> withView(View3d::undoEdit));
    redo.addActionListener(_ -> withView(View3d::redoEdit));
    whole.addActionListener(_ -> withView(View3d::restoreVolume));
    EventManager.getInstance().addMouseToolListener(() -> refresh(selectedView3d()));

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

  private static void withView(Consumer<View3d> action) {
    View3d view = selectedView3d();
    if (view != null) {
      action.accept(view);
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

    View3d view = hasView ? selectedView3d() : null;
    boolean idle = view != null && !view.isCutRunning();
    fitBone.setEnabled(idle && view.canAutoCropToBone());
    freeHand.setEnabled(view != null);
    String left = EventManager.getInstance().getMouseActions().getAction(MouseActions.T_LEFT);
    freeHand.setSelected(view != null && ActionVol.SCALPEL.cmd().equals(left));
    boolean area = idle && view.getLasso().isClosed();
    keepInside.setEnabled(area);
    removeInside.setEnabled(area);
    undo.setEnabled(idle && view.getEditHistory().canUndo());
    redo.setEnabled(idle && view.getEditHistory().canRedo());
    whole.setEnabled(idle && !view.isWholeVolumeShown());
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
