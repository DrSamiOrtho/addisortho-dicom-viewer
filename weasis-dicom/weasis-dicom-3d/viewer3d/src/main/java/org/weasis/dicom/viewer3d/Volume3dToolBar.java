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
package org.weasis.dicom.viewer3d;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JToggleButton;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.Feature;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.ActionIcon;
import org.weasis.core.api.util.ResourceUtil.ResourceIconPath;
import org.weasis.core.ui.editor.image.MouseActions;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.util.ToolbarLabels;
import org.weasis.core.ui.util.WtoolBar;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer2d.ResetTools;
import org.weasis.dicom.viewer3d.geometry.CameraView;
import org.weasis.dicom.viewer3d.vr.LassoCut;
import org.weasis.dicom.viewer3d.vr.View3d;
import org.weasis.dicom.viewer3d.vr.View3dSnapshot;
import org.weasis.dicom.viewer3d.vr.View3dVideoExport;

/**
 * The 3D tools bar: the mouse tools (rotate, roll, pan, zoom, window/level, scalpel, keep only
 * this, remove this, length, arrow), the scalpel commands with their undo history, the six
 * anatomical orientations, fit and reset, shading, and the image, movie and snapshot commands.
 * Every command has a key, listed in {@link Shortcuts3d}.
 */
public class Volume3dToolBar extends WtoolBar {

  /** An icon of the resources folder by path. */
  private record IconPath(String getPath) implements ResourceIconPath {}

  private final EventManager eventManager;
  private final Map<String, JToggleButton> toolButtons = new LinkedHashMap<>();
  private final Map<String, String> toolTips = new LinkedHashMap<>();
  private final ButtonGroup toolGroup = new ButtonGroup();
  private JButton undoButton;
  private JButton redoButton;
  private JButton restoreButton;
  private JButton cutInsideButton;
  private JButton cutOutsideButton;

  public Volume3dToolBar(int position) {
    super(Messages.getString("3d.tools.bar"), position);
    this.eventManager = EventManager.getInstance();
    initGui();
    eventManager.addMouseToolListener(this::refresh);
    refresh();
  }

  private void initGui() {
    addTool("Rotate", ActionW.ROTATION, ActionIcon.ROTATION, ActionW.ROTATION.getTitle() + " (R)");
    addTool("Roll", ActionVol.ROLL, icon("vol.roll"), ActionVol.ROLL.getTitle() + " (T)");
    addTool("Pan", ActionW.PAN, ActionIcon.PAN, ActionW.PAN.getTitle() + " (M)");
    addTool("Zoom", ActionW.ZOOM, ActionIcon.ZOOM, ActionW.ZOOM.getTitle() + " (Z)");
    addTool("W/L", ActionW.WINLEVEL, ActionIcon.WINDOW_LEVEL, ActionW.WINLEVEL.getTitle() + " (W)");
    addTool(
        "Scalpel",
        ActionVol.SCALPEL,
        icon("vol.scalpel"),
        Messages.getString("tool.scalpel.tip") + " (S)");
    addTool("Keep", ActionVol.KEEP_THIS, icon("vol.keep"), Messages.getString("tool.keep.tip"));
    addTool(
        "Remove", ActionVol.REMOVE_THIS, icon("vol.remove"), Messages.getString("tool.remove.tip"));
    addTool(
        "Length",
        ActionVol.LENGTH,
        icon("vol.length"),
        Messages.getString("tool.length.tip") + " (L)");
    addTool(
        "Arrow", ActionVol.ARROW, icon("vol.arrow"), Messages.getString("tool.arrow.tip") + " (Y)");
    addSeparator();

    cutInsideButton =
        addButton(
            "Cut in",
            icon("vol.cutInside"),
            Messages.getString("cut.inside"),
            () -> selectedView().ifPresent(v -> v.cutLasso(LassoCut.Side.INSIDE)));
    cutOutsideButton =
        addButton(
            "Cut out",
            icon("vol.cutOutside"),
            Messages.getString("cut.outside"),
            () -> selectedView().ifPresent(v -> v.cutLasso(LassoCut.Side.OUTSIDE)));
    undoButton =
        addButton(
            "Undo",
            icon("vol.undo"),
            Messages.getString("cut.undo"),
            () -> selectedView().ifPresent(View3d::undoEdit));
    redoButton =
        addButton(
            "Redo",
            icon("vol.redo"),
            Messages.getString("cut.redo"),
            () -> selectedView().ifPresent(View3d::redoEdit));
    restoreButton =
        addButton(
            "Whole",
            icon("vol.restore"),
            Messages.getString("cut.restore"),
            () -> selectedView().ifPresent(View3d::restoreVolume));
    JCheckBox separation = new JCheckBox(Messages.getString("cut.separation"));
    separation.addActionListener(
        _ -> selectedView().ifPresent(v -> v.setLightSeparation(separation.isSelected())));
    add(separation);
    addSeparator();

    for (StandardView view : StandardView.values()) {
      addPreset(view.letter(), Messages.getString(view.messageKey()), view.cameraView());
    }
    addButton(
        "Fit",
        icon("vol.fit"),
        Messages.getString("view.fit"),
        () -> selectedView().ifPresent(View3d::fitToVisible));
    addButton(
        "Reset",
        ActionIcon.RESET,
        Messages.getString("view.reset.scene"),
        () -> eventManager.reset(ResetTools.ALL));
    eventManager
        .getAction(ActionVol.VOL_SHADING)
        .ifPresent(
            b -> {
              JToggleButton shading = new JToggleButton();
              shading.setToolTipText(ActionVol.VOL_SHADING.getTitle() + " (Ctrl+S)"); // NON-NLS
              shading.setIcon(icon("vol.shading"));
              shading.putClientProperty(ToolbarLabels.CLIENT_PROPERTY, "Shade"); // NON-NLS
              b.registerActionState(shading);
              add(shading);
            });
    addSeparator();

    addButton(
        "Copy",
        ActionIcon.EXPORT_CLIPBOARD,
        Messages.getString("view.copy"),
        () -> selectedView().ifPresent(View3dSnapshot::copyToClipboard));
    addButton(
        "JPEG",
        ActionIcon.EXPORT_IMAGE,
        Messages.getString("view.save.jpeg"),
        () -> selectedView().ifPresent(View3dSnapshot::saveJpeg));
    addButton(
        "Movie",
        ActionIcon.LOOP,
        Messages.getString("view.movie"),
        () -> selectedView().ifPresent(View3d::toggleMovie));
    addButton(
        "Video",
        ActionIcon.EXPORT_IMAGE,
        Messages.getString("view.video"),
        () -> selectedView().ifPresent(View3dVideoExport::export));
    addButton(
        "Snap",
        icon("vol.snapshot"),
        Messages.getString("view.snapshot"),
        () -> selectedView().ifPresent(View3d::takeSnapshot));
    addButton(
        "Prev",
        ActionIcon.PREVIOUS,
        Messages.getString("view.snapshot.previous"),
        () -> selectedView().ifPresent(v -> v.showSnapshot(-1)));
    addButton(
        "Next",
        ActionIcon.NEXT,
        Messages.getString("view.snapshot.next"),
        () -> selectedView().ifPresent(v -> v.showSnapshot(1)));
    addButton(
        "Clear",
        ActionIcon.SELECTION_DELETE,
        Messages.getString("view.annotations.clear"),
        () -> selectedView().ifPresent(View3d::clearAnnotations));
    JButton help = new JButton("?"); // NON-NLS
    help.setToolTipText(Messages.getString("help.button"));
    help.setPreferredSize(GuiUtils.getBigIconButtonSize(help));
    help.addActionListener(
        _ ->
            JOptionPane.showMessageDialog(
                this,
                Shortcuts3d.helpHtml(),
                Messages.getString("help.title"),
                JOptionPane.INFORMATION_MESSAGE));
    add(help);
  }

  private static Icon icon(String name) {
    return ResourceUtil.getToolBarIcon(new IconPath("svg/action/" + name + ".svg")); // NON-NLS
  }

  private void addTool(String label, Feature<?> action, ActionIcon iconPath, String tip) {
    addTool(label, action, ResourceUtil.getToolBarIcon(iconPath), tip);
  }

  /** A mouse tool; {@code label} is the short text under the icon (tool bar labels on). */
  private void addTool(String label, Feature<?> action, Icon icon, String tip) {
    JToggleButton button = new JToggleButton(icon);
    button.setToolTipText(tip);
    button.putClientProperty(ToolbarLabels.CLIENT_PROPERTY, label);
    button.addActionListener(_ -> eventManager.changeLeftMouseAction(action.cmd()));
    toolGroup.add(button);
    toolButtons.put(action.cmd(), button);
    toolTips.put(action.cmd(), tip);
    add(button);
  }

  private JButton addButton(String label, ActionIcon iconPath, String tip, Runnable action) {
    return addButton(label, ResourceUtil.getToolBarIcon(iconPath), tip, action);
  }

  /** A command; {@code label} is the short text under the icon (tool bar labels on). */
  private JButton addButton(String label, Icon icon, String tip, Runnable action) {
    JButton button = new JButton(icon);
    button.setToolTipText(tip);
    button.putClientProperty(ToolbarLabels.CLIENT_PROPERTY, label);
    button.addActionListener(_ -> action.run());
    add(button);
    return button;
  }

  private void addPreset(String text, String tip, CameraView view) {
    JButton button = new JButton(text);
    button.setToolTipText(tip);
    button.setPreferredSize(GuiUtils.getBigIconButtonSize(button));
    button.addActionListener(_ -> selectedView().ifPresent(v -> Shortcuts3d.preset(v, view)));
    add(button);
  }

  private Optional<View3d> selectedView() {
    ViewCanvas<DicomImageElement> pane = eventManager.getSelectedViewPane();
    return pane instanceof View3d view3d ? Optional.of(view3d) : Optional.empty();
  }

  /** Shows which tool the left button has and what the cut history allows. */
  public void refresh() {
    String left = eventManager.getMouseActions().getAction(MouseActions.T_LEFT);
    JToggleButton active = toolButtons.get(left);
    if (active != null) {
      active.setSelected(true);
    } else {
      toolGroup.clearSelection();
    }
    Optional<View3d> view = selectedView();
    boolean hasLasso = view.map(v -> v.getLasso().isClosed() && !v.isCutRunning()).orElse(false);
    cutInsideButton.setEnabled(hasLasso);
    cutOutsideButton.setEnabled(hasLasso);
    boolean idle = view.map(v -> !v.isCutRunning()).orElse(false);
    undoButton.setEnabled(idle && view.map(v -> v.getEditHistory().canUndo()).orElse(false));
    redoButton.setEnabled(idle && view.map(v -> v.getEditHistory().canRedo()).orElse(false));
    restoreButton.setEnabled(idle && view.map(v -> !v.isWholeVolumeShown()).orElse(false));
    for (Map.Entry<String, JToggleButton> e : toolButtons.entrySet()) {
      e.getValue().setEnabled(view.isPresent());
      e.getValue().setToolTipText(toolTips.get(e.getKey()));
    }
    // The tools that pick a point of the volume wait for the segmentation they must honour, and
    // keep/remove have no anatomy to act on in the segmentation-only mode.
    String pick = view.map(View3d::pickUnavailableReason).orElse(null);
    String keepRemove = view.map(View3d::keepRemoveUnavailableReason).orElse(null);
    disableWithReason(ActionVol.LENGTH.cmd(), pick);
    disableWithReason(ActionVol.ARROW.cmd(), pick);
    disableWithReason(ActionVol.KEEP_THIS.cmd(), keepRemove);
    disableWithReason(ActionVol.REMOVE_THIS.cmd(), keepRemove);
  }

  private void disableWithReason(String cmd, String reason) {
    JToggleButton button = toolButtons.get(cmd);
    if (button != null && reason != null) {
      button.setEnabled(false);
      button.setToolTipText(reason);
    }
  }
}
