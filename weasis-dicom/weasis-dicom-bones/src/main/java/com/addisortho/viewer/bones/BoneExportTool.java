/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import bibliothek.gui.dock.common.CLocation;
import com.addisortho.viewer.bones.BoneExportWorker.Outcome;
import com.addisortho.viewer.bones.BoneExportWorker.Problem;
import com.addisortho.viewer.bones.surface.BoneSurfaceBuilder.Result;
import com.addisortho.viewer.bones.surface.SurfaceOptions;
import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter;
import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker.StateValue;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.model.graphic.imp.seg.SegRegion;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.View3DContainer;
import org.weasis.dicom.viewer3d.vr.DicomVolTexture;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Tool of the 3D view that exports one segment of the shown segmentation as an STL surface, for 3D
 * printing and planning.
 */
public class BoneExportTool extends PluginTool implements SeriesViewerListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(BoneExportTool.class);

  public static final String BUTTON_NAME = Messages.getString("BoneExportTool.title");

  static final String P_LAST_DIR = "bones.stl.last.dir"; // NON-NLS

  /** The working grid may take a fifth of the free heap, at 5 bytes per sample. */
  private static final long FREE_BYTES_PER_SAMPLE = 25;

  private static final long MIN_VOXELS = 2_000_000L;

  private final JComboBox<SegmentChoice> segmentCombo = new JComboBox<>();
  private final JSpinner thresholdSpinner =
      new JSpinner(
          new SpinnerNumberModel((int) SurfaceOptions.DEFAULT_THRESHOLD_HU, -1000, 3000, 10));
  private final JCheckBox largestPieceBox =
      new JCheckBox(Messages.getString("BoneExportTool.largest"), true);
  private final JSpinner trianglesSpinner =
      new JSpinner(new SpinnerNumberModel(0, 0, 50_000_000, 100_000));
  private final JButton exportButton = new JButton(Messages.getString("BoneExportTool.export"));
  private final JButton cancelButton = new JButton(Messages.getString("BoneExportTool.cancel"));
  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JLabel statusLabel = new JLabel(" ");
  private final JScrollPane rootPane = new JScrollPane();
  private BoneExportWorker worker;

  public BoneExportTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 40);
    dockable.setTitleIcon(ResourceUtil.getIcon(OtherIcon.VIEW_3D));
    setDockableWidth(320);
    initGui();
    refreshSegments();
    // The tool may be created before the 3D view holds its volume: list again once shown.
    addHierarchyListener(
        e -> {
          if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
              && isShowing()
              && worker == null) {
            refreshSegments();
          }
        });
  }

  @Override
  public Component getToolComponent() {
    return getToolComponentFromJScrollPane(rootPane);
  }

  private void initGui() {
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
    rootPane.setBorder(BorderFactory.createEmptyBorder());
    JPanel panel = GuiUtils.getVerticalBoxLayoutPanel();
    panel.setBorder(GuiUtils.getEmptyBorder(10, 5, 5, 5));
    int gap = 7;

    segmentCombo.setMaximumRowCount(20);
    GuiUtils.setPreferredWidth(segmentCombo, 200);
    // The list follows the 3D view: rebuild it whenever it is opened.
    segmentCombo.addPopupMenuListener(
        new PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            refreshSegments();
          }

          @Override
          public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
            // nothing to do
          }

          @Override
          public void popupMenuCanceled(PopupMenuEvent e) {
            // nothing to do
          }
        });
    panel.add(row(Messages.getString("BoneExportTool.segment"), segmentCombo));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    thresholdSpinner.setToolTipText(Messages.getString("BoneExportTool.threshold.tip"));
    panel.add(row(Messages.getString("BoneExportTool.threshold"), thresholdSpinner));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    largestPieceBox.setToolTipText(Messages.getString("BoneExportTool.largest.tip"));
    panel.add(GuiUtils.getFlowLayoutPanel(largestPieceBox));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    trianglesSpinner.setToolTipText(Messages.getString("BoneExportTool.triangles.tip"));
    panel.add(row(Messages.getString("BoneExportTool.triangles"), trianglesSpinner));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    exportButton.setToolTipText(Messages.getString("BoneExportTool.export.tip"));
    exportButton.addActionListener(_ -> export());
    cancelButton.addActionListener(_ -> cancel());
    cancelButton.setEnabled(false);
    panel.add(GuiUtils.getFlowLayoutPanel(exportButton, cancelButton));

    progressBar.setStringPainted(true);
    progressBar.setVisible(false);
    panel.add(GuiUtils.getHorizontalBoxLayoutPanel(progressBar));
    panel.add(GuiUtils.getFlowLayoutPanel(statusLabel));
    panel.add(GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneExportTool.note"))));
    add(panel);
    add(GuiUtils.boxYLastElement(3));
  }

  private static JPanel row(String title, JComponent field) {
    return GuiUtils.getHorizontalBoxLayoutPanel(5, new JLabel(title + StringUtil.COLON), field);
  }

  /** The 3D view holding a volume, if the selected view is one. */
  private static Optional<View3d> selectedView() {
    if (EventManager.getInstance().getSelectedViewPane() instanceof View3d view
        && view.getVolTexture() != null) {
      return Optional.of(view);
    }
    return Optional.empty();
  }

  /** Lists the segments of the selected 3D view, keeping the current choice when still listed. */
  private void refreshSegments() {
    Object previous = segmentCombo.getSelectedItem();
    List<SegmentChoice> choices =
        selectedView()
            .map(v -> SegmentChoice.of(v.getVolTexture().getSegmentations()))
            .orElse(List.of());
    segmentCombo.setModel(new DefaultComboBoxModel<>(choices.toArray(new SegmentChoice[0])));
    if (!choices.isEmpty()) {
      int index = choices.indexOf(previous);
      segmentCombo.setSelectedIndex(
          index >= 0 ? index : SegmentChoice.defaultIndex(choices, regionMap()));
    }
  }

  /** Tick state of the 3D Segmentation tool's tree. */
  private static Map<String, List<SegRegion<?>>> regionMap() {
    if (EventManager.getInstance().getSelectedView2dContainer() instanceof View3DContainer c
        && c.getRegionMap() != null) {
      return c.getRegionMap();
    }
    return Map.of();
  }

  private void export() {
    if (segmentCombo.getItemCount() == 0) {
      refreshSegments();
    }
    Optional<View3d> view = selectedView();
    if (!(segmentCombo.getSelectedItem() instanceof SegmentChoice choice) || view.isEmpty()) {
      showMessage(Messages.getString("BoneExportTool.noSegment"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    Optional<Path> file = chooseFile(choice);
    if (file.isEmpty()) {
      return;
    }
    DicomVolTexture texture = view.get().getVolTexture();
    SurfaceOptions options =
        SurfaceOptions.defaults()
            .withThreshold(((Number) thresholdSpinner.getValue()).doubleValue())
            .withKeepLargestPiece(largestPieceBox.isSelected())
            .withMaxTriangles(((Number) trianglesSpinner.getValue()).intValue())
            .withMaxVoxels(voxelBudget());
    worker =
        new BoneExportWorker(
            choice, texture.getVolume(), texture.getModality(), options, file.get());
    worker.addPropertyChangeListener(this::workerChanged);
    setRunning(true);
    worker.execute();
  }

  private void cancel() {
    if (worker != null) {
      worker.cancel(true);
    }
  }

  private void setRunning(boolean running) {
    exportButton.setEnabled(!running);
    cancelButton.setEnabled(running);
    segmentCombo.setEnabled(!running);
    thresholdSpinner.setEnabled(!running);
    largestPieceBox.setEnabled(!running);
    trianglesSpinner.setEnabled(!running);
    progressBar.setVisible(running);
    progressBar.setValue(0);
  }

  private void workerChanged(PropertyChangeEvent evt) {
    BoneExportWorker source = (BoneExportWorker) evt.getSource();
    switch (evt.getPropertyName()) {
      case "progress" -> progressBar.setValue((Integer) evt.getNewValue()); // NON-NLS
      case BoneExportWorker.STAGE -> statusLabel.setText((String) evt.getNewValue());
      case "state" -> { // NON-NLS
        if (evt.getNewValue() == StateValue.DONE) {
          finished(source);
        }
      }
      default -> {
        // other properties are not shown
      }
    }
  }

  private void finished(BoneExportWorker done) {
    worker = null;
    setRunning(false);
    try {
      Outcome outcome = done.get();
      statusLabel.setText(
          String.format(Messages.getString("BoneExportTool.saved"), outcome.file().getFileName()));
      showMessage(report(outcome, done.options()), JOptionPane.INFORMATION_MESSAGE);
    } catch (CancellationException e) {
      statusLabel.setText(Messages.getString("BoneExportTool.cancelled"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      statusLabel.setText(" ");
      if (e.getCause() instanceof Problem problem) {
        showMessage(problem.getMessage(), JOptionPane.WARNING_MESSAGE);
      } else {
        LOGGER.error("Bone STL export", e.getCause());
        showMessage(
            String.format(Messages.getString("BoneExportTool.failed"), e.getCause()),
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private static String report(Outcome outcome, SurfaceOptions options) {
    Result r = outcome.result();
    StringBuilder text = new StringBuilder();
    text.append(
        String.format(Messages.getString("BoneExportTool.saved"), outcome.file().getFileName()));
    text.append('\n');
    text.append(
        String.format(
            Messages.getString("BoneExportTool.size"),
            r.mesh().triangleCount(),
            r.mesh().signedVolume() / 1000,
            r.spacingMm()));
    text.append('\n');
    if (outcome.withoutCt() == null) {
      text.append(
          String.format(
              Messages.getString("BoneExportTool.fromCt"), r.iso(), options.growVoxels()));
    } else {
      text.append(String.format(Messages.getString("BoneExportTool.fromSeg"), outcome.withoutCt()));
    }
    text.append('\n');
    text.append(
        String.format(
            Messages.getString("BoneExportTool.pieces"), r.piecesFound(), r.piecesKept()));
    text.append("\n\n");
    text.append(Messages.getString("BoneExportTool.coordinates"));
    return text.toString();
  }

  /** Asks where to save, suggesting the segment label and today's date. */
  private Optional<Path> chooseFile(SegmentChoice choice) {
    WProperties prefs = GuiUtils.getUICore().getLocalPersistence();
    SystemFileChooser chooser = new SystemFileChooser(prefs.getProperty(P_LAST_DIR, ""));
    chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
    chooser.setAcceptAllFileFilterUsed(false);
    chooser.setFileFilter(
        new FileNameExtensionFilter(
            Messages.getString("BoneExportTool.fileType"), "stl")); // NON-NLS
    chooser.setSelectedFile(new File(choice.defaultFileName(LocalDate.now())));
    if (chooser.showSaveDialog(WinUtil.getParentWindow(this)) != SystemFileChooser.APPROVE_OPTION
        || chooser.getSelectedFile() == null) {
      return Optional.empty();
    }
    Path chosen = chooser.getSelectedFile().toPath();
    String name = chosen.getFileName().toString();
    Path file =
        name.toLowerCase(Locale.ROOT).endsWith(".stl") // NON-NLS
            ? chosen
            : chosen.resolveSibling(name + ".stl"); // NON-NLS
    // The dialog confirmed replacing the file it returned, not one whose extension was added here.
    if (!file.equals(chosen)
        && Files.exists(file)
        && JOptionPane.showConfirmDialog(
                WinUtil.getParentWindow(this),
                String.format(Messages.getString("BoneExportTool.overwrite"), file.getFileName()),
                BUTTON_NAME,
                JOptionPane.YES_NO_OPTION)
            != JOptionPane.YES_OPTION) {
      return Optional.empty();
    }
    if (file.getParent() != null) {
      prefs.setProperty(P_LAST_DIR, file.getParent().toString());
    }
    return Optional.of(file);
  }

  /** Working grid size the free heap can take, leaving room for the mesh and the viewer. */
  static long voxelBudget() {
    Runtime rt = Runtime.getRuntime();
    long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
    return Math.clamp(free / FREE_BYTES_PER_SAMPLE, MIN_VOXELS, SurfaceOptions.DEFAULT_MAX_VOXELS);
  }

  private void showMessage(String message, int type) {
    JOptionPane.showMessageDialog(WinUtil.getParentWindow(this), message, BUTTON_NAME, type);
  }

  @Override
  public void changingViewContentEvent(SeriesViewerEvent event) {
    EVENT e = event.getEventType();
    if ((EVENT.SELECT_VIEW.equals(e) || EVENT.SELECT.equals(e)) && worker == null) {
      refreshSegments();
    }
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // the layout does not depend on the side
  }
}
