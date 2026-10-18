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
import com.addisortho.viewer.bones.BoneExportWorker.Problem;
import com.addisortho.viewer.bones.BoneGrowWorker.Outcome;
import com.addisortho.viewer.bones.BoneGrowWorker.Request;
import com.addisortho.viewer.bones.SeedPicker.Seed;
import com.addisortho.viewer.bones.grow.BonePreset;
import com.addisortho.viewer.bones.grow.GrowOptions;
import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.seg.SegmentationBuilder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.beans.PropertyChangeEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker.StateValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * The Bones panel of the 3D view: selects a bone by region growing from one click on it, in the 3D
 * view or in an MPR view, and adds it as a segment of a DICOM Segmentation of the CT, so that the
 * segmentation display, smooth surfaces and the Bone STL export work on it.
 *
 * <p>Made to be used without training: a bone type sets every setting ({@link BonePreset}), the
 * seed is shown as a target in the 3D view with its CT value, the threshold can be previewed on the
 * 3D view, the bone is selected as soon as it is clicked, and Undo and Redo take segments out and
 * put them back. The detailed settings stay under More settings.
 */
public class BoneGrowTool extends PluginTool implements SeriesViewerListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(BoneGrowTool.class);

  public static final String BUTTON_NAME = Messages.getString("BoneGrowTool.title");

  /** A region above this volume has most likely leaked out of the bone. */
  private static final double LARGE_CM3 = 1500;

  private final JComboBox<String> presetBox = new JComboBox<>();
  private final JToggleButton pickButton =
      new JToggleButton(Messages.getString("BoneGrowTool.pick"));
  private final JCheckBox autoGrowBox =
      new JCheckBox(Messages.getString("BoneGrowTool.autoGrow"), true);
  private final JCheckBox previewBox = new JCheckBox(Messages.getString("BoneGrowTool.preview"));
  private final JCheckBox moreBox = new JCheckBox(Messages.getString("BoneGrowTool.more"));
  private final JPanel morePanel = GuiUtils.getVerticalBoxLayoutPanel();
  private final JButton undoButton = new JButton(Messages.getString("BoneGrowTool.undo"));
  private final JButton redoButton = new JButton(Messages.getString("BoneGrowTool.redo"));
  private final JButton crosshairButton = new JButton(Messages.getString("BoneGrowTool.crosshair"));
  private final JLabel seedLabel = new JLabel(Messages.getString("BoneGrowTool.noSeed"));
  private final JSpinner lowerSpinner =
      new JSpinner(new SpinnerNumberModel((int) GrowOptions.DEFAULT_LOWER_HU, -1024, 30_000, 10));
  private final JSpinner upperSpinner =
      new JSpinner(new SpinnerNumberModel((int) GrowOptions.DEFAULT_UPPER_HU, -1024, 30_000, 10));
  private final JSpinner closingSpinner =
      new JSpinner(new SpinnerNumberModel(1, 0, GrowOptions.MAX_RADIUS, 1));
  private final JSpinner separationSpinner =
      new JSpinner(new SpinnerNumberModel(0, 0, GrowOptions.MAX_RADIUS, 1));
  private final JCheckBox fillBox = new JCheckBox(Messages.getString("BoneGrowTool.fill"), true);
  private final JSpinner minPieceSpinner =
      new JSpinner(new SpinnerNumberModel(100, 0, 1_000_000, 50));
  private final JCheckBox cropBox = new JCheckBox(Messages.getString("BoneGrowTool.crop"), true);
  private final JTextField nameField = new JTextField(14);
  private final JButton colorButton = new JButton(Messages.getString("BoneGrowTool.color"));
  private final JButton growButton = new JButton(Messages.getString("BoneGrowTool.grow"));
  private final JButton cancelButton = new JButton(Messages.getString("BoneGrowTool.cancel"));
  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JLabel statusLabel = new JLabel(" ");
  private final JLabel sessionLabel = new JLabel(" ");
  private final JScrollPane rootPane = new JScrollPane();

  private final SeedPicker picker = new SeedPicker();
  private final SegExplorer explorer = new SegExplorer();
  private final Map<String, GrowSession> sessions = new HashMap<>();
  private Seed seed;
  private Color color;
  private CtSource source;
  private BoneGrowWorker worker;
  private GrowSession.Change pendingChange = GrowSession.Change.GROWN;

  public BoneGrowTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 41);
    dockable.setTitleIcon(ResourceUtil.getIcon(OtherIcon.VIEW_3D));
    setDockableWidth(320);
    initGui();
    refreshSession();
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

    panel.add(GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.how"))));
    for (BonePreset preset : BonePreset.ALL) {
      presetBox.addItem(Messages.getString(preset.key()));
    }
    presetBox.setToolTipText(Messages.getString("BoneGrowTool.preset.tip"));
    presetBox.addActionListener(_ -> applyPreset());
    panel.add(row(Messages.getString("BoneGrowTool.preset"), presetBox));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    pickButton.setToolTipText(Messages.getString("BoneGrowTool.pick.tip"));
    pickButton.addActionListener(_ -> togglePicking());
    crosshairButton.setToolTipText(Messages.getString("BoneGrowTool.crosshair.tip"));
    crosshairButton.addActionListener(_ -> useCrosshair());
    panel.add(GuiUtils.getFlowLayoutPanel(pickButton, crosshairButton));
    autoGrowBox.setToolTipText(Messages.getString("BoneGrowTool.autoGrow.tip"));
    panel.add(GuiUtils.getFlowLayoutPanel(autoGrowBox));
    panel.add(GuiUtils.getFlowLayoutPanel(seedLabel));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    String rangeTip = Messages.getString("BoneGrowTool.range.tip");
    lowerSpinner.setToolTipText(rangeTip);
    upperSpinner.setToolTipText(rangeTip);
    lowerSpinner.addChangeListener(_ -> thresholdChanged());
    panel.add(row(Messages.getString("BoneGrowTool.lower"), lowerSpinner));
    previewBox.setToolTipText(Messages.getString("BoneGrowTool.preview.tip"));
    previewBox.addActionListener(_ -> updatePreview());
    panel.add(GuiUtils.getFlowLayoutPanel(previewBox));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    growButton.setToolTipText(Messages.getString("BoneGrowTool.grow.tip"));
    growButton.addActionListener(_ -> grow());
    cancelButton.addActionListener(_ -> cancel());
    cancelButton.setEnabled(false);
    panel.add(GuiUtils.getFlowLayoutPanel(growButton, cancelButton));
    progressBar.setStringPainted(true);
    progressBar.setVisible(false);
    panel.add(GuiUtils.getHorizontalBoxLayoutPanel(progressBar));
    panel.add(GuiUtils.getFlowLayoutPanel(statusLabel));
    undoButton.setToolTipText(Messages.getString("BoneGrowTool.undo.tip"));
    redoButton.setToolTipText(Messages.getString("BoneGrowTool.redo.tip"));
    undoButton.addActionListener(_ -> undo());
    redoButton.addActionListener(_ -> redo());
    panel.add(GuiUtils.getFlowLayoutPanel(undoButton, redoButton));
    panel.add(GuiUtils.getFlowLayoutPanel(sessionLabel));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    moreBox.addActionListener(_ -> morePanel.setVisible(moreBox.isSelected()));
    panel.add(GuiUtils.getFlowLayoutPanel(moreBox));
    morePanel.add(row(Messages.getString("BoneGrowTool.upper"), upperSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    closingSpinner.setToolTipText(Messages.getString("BoneGrowTool.closing.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.closing"), closingSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    separationSpinner.setToolTipText(Messages.getString("BoneGrowTool.separation.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.separation"), separationSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    morePanel.add(GuiUtils.getFlowLayoutPanel(fillBox));
    minPieceSpinner.setToolTipText(Messages.getString("BoneGrowTool.minPiece.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.minPiece"), minPieceSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    morePanel.add(GuiUtils.getFlowLayoutPanel(cropBox));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    morePanel.add(row(Messages.getString("BoneGrowTool.name"), nameField));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    colorButton.addActionListener(_ -> chooseColor());
    morePanel.add(GuiUtils.getFlowLayoutPanel(colorButton));
    morePanel.setVisible(false);
    panel.add(morePanel);
    panel.add(GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.note"))));
    add(panel);
    add(GuiUtils.boxYLastElement(3));
    presetBox.setSelectedIndex(0);
    applyPreset();
  }

  private BonePreset selectedPreset() {
    int i = presetBox.getSelectedIndex();
    return i < 0 ? BonePreset.OTHER : BonePreset.ALL[i];
  }

  /** Puts the settings of the chosen bone type in every field. */
  private void applyPreset() {
    BonePreset p = selectedPreset();
    lowerSpinner.setValue((int) p.lowerHu());
    upperSpinner.setValue((int) GrowOptions.DEFAULT_UPPER_HU);
    closingSpinner.setValue(p.closingRadius());
    separationSpinner.setValue(p.separationVoxels());
    fillBox.setSelected(true);
    minPieceSpinner.setValue((int) p.minPieceMm3());
    refreshSession();
  }

  private void thresholdChanged() {
    updatePreview();
    showSeed();
  }

  /** Shows or ends the threshold preview on the 3D view. */
  private void updatePreview() {
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      return;
    }
    if (previewBox.isSelected()) {
      view.get().previewThreshold(((Number) lowerSpinner.getValue()).doubleValue());
    } else if (view.get().isPreviewingThreshold()) {
      view.get().previewThreshold(null);
    }
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

  /** The CT of the view, read again only when the view shows another volume. */
  private CtSource source(View3d view) throws Problem {
    if (source == null || source.volume() != view.getVolTexture().getVolume()) {
      source = CtSource.of(view);
    }
    return source;
  }

  private Optional<CtSource> currentSource() {
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(source(view.get()));
    } catch (Problem e) {
      return Optional.empty();
    }
  }

  private GrowSession session(CtSource ct) {
    return sessions.computeIfAbsent(ct.seriesUID(), GrowSession::new);
  }

  private void togglePicking() {
    if (pickButton.isSelected()) {
      seedLabel.setText(Messages.getString("BoneGrowTool.picking"));
      picker.arm(
          picked -> {
            pickButton.setSelected(false);
            setSeed(picked);
            if (autoGrowBox.isSelected() && worker == null) {
              grow();
            }
          },
          missed -> seedLabel.setText(missed));
    } else {
      picker.disarm();
      showSeed();
    }
  }

  private void useCrosshair() {
    Optional<CtSource> ct = currentSource();
    String frame = ct.map(CtSource::frameOfReferenceUID).orElse(null);
    Optional<Seed> found = SeedPicker.fromCrosshair(frame);
    if (found.isEmpty()) {
      showMessage(Messages.getString("BoneGrowTool.noCrosshair"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    setSeed(found.get());
  }

  private void setSeed(Seed picked) {
    seed = picked;
    selectedView().ifPresent(v -> v.setSeedMarker(picked == null ? null : picked.lps()));
    showSeed();
  }

  /** Shows the seed, with its CT value when it falls in the CT of the 3D view. */
  private void showSeed() {
    if (seed == null) {
      seedLabel.setText(Messages.getString("BoneGrowTool.noSeed"));
      return;
    }
    double hu =
        currentSource()
            .flatMap(ct -> ct.voxelOf(seed.lps()).map(v -> ct.huSource().huAt(v[0], v[1], v[2])))
            .orElse(Double.NaN);
    String plane = seed.plane().toLowerCase(Locale.ROOT);
    if (Double.isNaN(hu)) {
      seedLabel.setText(
          String.format(
              Messages.getString("BoneGrowTool.seed"),
              plane,
              seed.lps().x,
              seed.lps().y,
              seed.lps().z));
      return;
    }
    double lower = ((Number) lowerSpinner.getValue()).doubleValue();
    String verdict =
        Messages.getString(hu >= lower ? "BoneGrowTool.seedInBone" : "BoneGrowTool.seedBelow");
    seedLabel.setText(String.format(Messages.getString("BoneGrowTool.seedHu"), plane, hu, verdict));
  }

  private void chooseColor() {
    Color chosen = JColorChooser.showDialog(WinUtil.getParentWindow(this), BUTTON_NAME, color);
    if (chosen != null) {
      setColor(chosen);
    }
  }

  private void setColor(Color c) {
    color = c;
    colorButton.setIcon(new ColorIcon(c));
  }

  /** Name, colour and segment count of the session of the shown CT. */
  private void refreshSession() {
    Optional<GrowSession> session = currentSource().map(this::session);
    int count = session.map(s -> s.segments().size()).orElse(0);
    int next = session.map(GrowSession::nextSegmentNumber).orElse(1);
    nameField.setText(
        String.format(
            Messages.getString("BoneGrowTool.defaultName"),
            Messages.getString(selectedPreset().key()),
            next));
    setColor(session.map(GrowSession::nextColor).orElse(new Color(241, 214, 145)));
    sessionLabel.setText(String.format(Messages.getString("BoneGrowTool.session"), count));
    undoButton.setEnabled(worker == null && session.map(GrowSession::canUndo).orElse(false));
    redoButton.setEnabled(worker == null && session.map(GrowSession::canRedo).orElse(false));
  }

  private void grow() {
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      showMessage(Messages.getString("BoneGrowTool.noCt"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    if (seed == null) {
      showMessage(Messages.getString("BoneGrowTool.needSeed"), JOptionPane.WARNING_MESSAGE);
      return;
    }
    try {
      CtSource ct = source(view.get());
      if (seed.frameOfReferenceUID() != null
          && ct.frameOfReferenceUID() != null
          && !Objects.equals(seed.frameOfReferenceUID(), ct.frameOfReferenceUID())) {
        throw new Problem(Messages.getString("BoneGrowTool.otherFrame"));
      }
      int[] voxel =
          ct.voxelOf(seed.lps())
              .orElseThrow(() -> new Problem(Messages.getString("BoneGrowTool.seedOutside")));
      VoxelBox crop = cropBox.isSelected() ? ct.cropBox(view.get()).orElse(null) : null;
      GrowSession session = session(ct);
      String label = nameField.getText().trim();
      if (label.isEmpty()) {
        label =
            String.format(
                Messages.getString("BoneGrowTool.defaultName"),
                Messages.getString(selectedPreset().key()),
                session.nextSegmentNumber());
      }
      Request request =
          new Request(voxel, crop, options(ct), session.nextSegmentNumber(), label, color);
      pendingChange = GrowSession.Change.GROWN;
      start(
          new BoneGrowWorker(
              ct,
              request,
              session.segments(),
              session.segSeriesUID(),
              session.nextInstanceNumber()));
    } catch (Problem e) {
      showMessage(e.getMessage(), JOptionPane.WARNING_MESSAGE);
    }
  }

  /** The settings in the fields, which a bone type fills and More settings lets one change. */
  private GrowOptions options(CtSource ct) {
    int lower = ((Number) lowerSpinner.getValue()).intValue();
    int upper = ((Number) upperSpinner.getValue()).intValue();
    double mm3 = ((Number) minPieceSpinner.getValue()).doubleValue();
    return GrowOptions.defaults()
        .withRange(Math.min(lower, upper), Math.max(lower, upper))
        .withClosingRadius(((Number) closingSpinner.getValue()).intValue())
        .withSeparation(((Number) separationSpinner.getValue()).intValue())
        .withFillHoles(fillBox.isSelected())
        .withMinPieceVoxels(Math.round(mm3 / ct.grid().voxelVolumeMm3()));
  }

  /** Takes the last segment out of the segmentation; Redo puts it back. */
  private void undo() {
    Optional<CtSource> ct = currentSource();
    if (ct.isEmpty() || worker != null) {
      return;
    }
    GrowSession session = session(ct.get());
    if (!session.canUndo()) {
      return;
    }
    pendingChange = GrowSession.Change.UNDONE;
    start(
        new BoneGrowWorker(
            ct.get(),
            null,
            session.withoutLast(),
            session.segSeriesUID(),
            session.nextInstanceNumber()));
  }

  /** Puts back the last segment taken out by Undo. */
  private void redo() {
    Optional<CtSource> ct = currentSource();
    if (ct.isEmpty() || worker != null) {
      return;
    }
    GrowSession session = session(ct.get());
    if (!session.canRedo()) {
      return;
    }
    pendingChange = GrowSession.Change.REDONE;
    start(
        new BoneGrowWorker(
            ct.get(),
            null,
            session.withRedone(),
            session.segSeriesUID(),
            session.nextInstanceNumber()));
  }

  private void start(BoneGrowWorker w) {
    worker = w;
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
    growButton.setEnabled(!running);
    cancelButton.setEnabled(running);
    undoButton.setEnabled(!running);
    redoButton.setEnabled(!running);
    progressBar.setVisible(running);
    progressBar.setIndeterminate(false);
    progressBar.setValue(0);
  }

  private void workerChanged(PropertyChangeEvent evt) {
    BoneGrowWorker source = (BoneGrowWorker) evt.getSource();
    switch (evt.getPropertyName()) {
      case "progress" -> progressBar.setValue((Integer) evt.getNewValue()); // NON-NLS
      case BoneGrowWorker.STAGE -> {
        String text = (String) evt.getNewValue();
        statusLabel.setText(text);
        // The grow cannot know how far the region will reach.
        progressBar.setIndeterminate(Messages.getString("BoneGrowTool.stage.GROWING").equals(text));
      }
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

  private void finished(BoneGrowWorker done) {
    worker = null;
    setRunning(false);
    try {
      Outcome outcome = done.get();
      CtSource ct = done.ct();
      GrowSession session = session(ct);
      String oldSop = session.sopInstanceUID();
      session.written(outcome.segments(), outcome.sopInstanceUID(), pendingChange);
      explorer.replace(
          ct.series(), session.segSeriesUID(), oldSop, outcome.file(), outcome.sopInstanceUID());
      if (outcome.result() == null) {
        statusLabel.setText(
            Messages.getString(
                pendingChange == GrowSession.Change.REDONE
                    ? "BoneGrowTool.redone"
                    : "BoneGrowTool.removed"));
      } else {
        report(done.request(), outcome, ct);
      }
    } catch (CancellationException e) {
      statusLabel.setText(Messages.getString("BoneGrowTool.cancelled"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      statusLabel.setText(" ");
      if (e.getCause() instanceof Problem problem) {
        showMessage(problem.getMessage(), JOptionPane.WARNING_MESSAGE);
      } else {
        LOGGER.error("Bone region grow", e.getCause());
        showMessage(
            String.format(Messages.getString("BoneGrowTool.failed"), e.getCause()),
            JOptionPane.ERROR_MESSAGE);
      }
    }
    refreshSession();
  }

  private void report(Request request, Outcome outcome, CtSource ct) {
    Result r = outcome.result();
    double cm3 = r.volumeCm3(ct.grid().voxelVolumeMm3());
    statusLabel.setText(
        String.format(Messages.getString("BoneGrowTool.done"), request.label(), r.voxels(), cm3));
    String steps =
        request.options().separationVoxels() == 0
            ? ""
            : Messages.getString(
                r.separated() ? "BoneGrowTool.separated" : "BoneGrowTool.notSeparated");
    String dropped =
        r.piecesDropped() == 0
            ? ""
            : String.format(Messages.getString("BoneGrowTool.dropped"), r.piecesDropped());
    String text =
        String.format(
            Messages.getString("BoneGrowTool.report"),
            request.label(),
            r.voxels(),
            cm3,
            r.grownVoxels(),
            r.seedHu(),
            steps,
            dropped,
            SegmentationBuilder.SERIES_DESCRIPTION,
            outcome.segments().size());
    if (cm3 > LARGE_CM3) {
      text = text.replace("</html>", Messages.getString("BoneGrowTool.large") + "</html>");
    }
    showMessage(
        text, cm3 > LARGE_CM3 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
  }

  private void showMessage(String message, int type) {
    JOptionPane.showMessageDialog(WinUtil.getParentWindow(this), message, BUTTON_NAME, type);
  }

  @Override
  public void changingViewContentEvent(SeriesViewerEvent event) {
    EVENT e = event.getEventType();
    if ((EVENT.SELECT_VIEW.equals(e) || EVENT.SELECT.equals(e)) && worker == null) {
      refreshSession();
      showSeed();
    }
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // the layout does not depend on the side
  }

  /** A small filled square of the segment colour. */
  private record ColorIcon(Color color) implements Icon {
    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
      g.setColor(color);
      g.fillRect(x, y, getIconWidth(), getIconHeight());
      g.setColor(Color.DARK_GRAY);
      g.drawRect(x, y, getIconWidth() - 1, getIconHeight() - 1);
    }

    @Override
    public int getIconWidth() {
      return 14;
    }

    @Override
    public int getIconHeight() {
      return 14;
    }
  }
}
