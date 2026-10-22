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
import com.addisortho.viewer.bones.BoneGrowWorker.Request;
import com.addisortho.viewer.bones.GrowSession.Found;
import com.addisortho.viewer.bones.SeedPicker.Seed;
import com.addisortho.viewer.bones.grow.BonePreset;
import com.addisortho.viewer.bones.grow.BoneSplit.Split;
import com.addisortho.viewer.bones.grow.BoneSplit.SplitException;
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.GrowOptions;
import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.VoxelBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.beans.PropertyChangeEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractButton;
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
import javax.swing.JToggleButton;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.SwingWorker.StateValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.util.ResourceUtil;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.ui.docking.PluginTool;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.SeriesViewerListener;
import org.weasis.core.ui.editor.image.MouseActions;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.vr.EditHistory;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * The Bones panel of the 3D view: the bones selected on the CT as a list, and three click tools
 * that work alike in the 3D view and in the MPR views of the CT.
 *
 * <ul>
 *   <li>New bone: a click on a bone grows it from there (region grow) and adds it to the list. The
 *       bones already selected act as walls, so no voxel belongs to two bones.
 *   <li>Erase piece: a click on an unwanted part of a bone (a leak into the table or a vessel) cuts
 *       it off at its narrowest link and erases it.
 *   <li>Split: a click on a part of a bone makes that part a bone of its own, cut off at the
 *       narrowest link (the femoral head grown with the acetabulum).
 * </ul>
 *
 * <p>Each bone of the list can be shown alone or hidden in the 3D picture, renamed, recoloured and
 * deleted. Every step goes into the undo history of the 3D view, with its cuts and crops, so Undo
 * (Ctrl+Z) walks back through all of them. The bones are written as one DICOM Segmentation of the
 * CT in the background after each step, so the segmentation display, smooth surfaces and the Bone
 * STL export work on them. The grow settings stay under More settings.
 */
public class BoneGrowTool extends PluginTool implements SeriesViewerListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(BoneGrowTool.class);

  public static final String BUTTON_NAME = Messages.getString("BoneGrowTool.title");

  /** A region above this volume has most likely leaked out of the bone. */
  private static final double LARGE_CM3 = 1500;

  /** How far from a bone, in voxels, a click on its surface still counts as on it. */
  private static final int NEAR_BONE_VOXELS = 3;

  /** The 3D tools a click sets off, which must not act on the clicks of the Bones tools. */
  private static final Set<String> CLICK_TOOLS =
      Set.of(
          ActionVol.KEEP_THIS.cmd(),
          ActionVol.REMOVE_THIS.cmd(),
          ActionVol.LENGTH.cmd(),
          ActionVol.ARROW.cmd(),
          ActionVol.SCALPEL.cmd());

  /** The click tools of the panel. */
  private enum Tool {
    NEW("BoneGrowTool.new", "BoneGrowTool.new.hint"),
    ERASE("BoneGrowTool.erase", "BoneGrowTool.erase.hint"),
    SPLIT("BoneGrowTool.split", "BoneGrowTool.split.hint");

    final String label;
    final String hint;

    Tool(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }
  }

  private final JComboBox<BoneNames.Region> regionBox =
      new JComboBox<>(BoneNames.REGIONS.toArray(BoneNames.Region[]::new));
  private final JComboBox<BoneNames.Name> boneBox = new JComboBox<>();
  private final JComboBox<BoneNames.Side> sideBox = new JComboBox<>(BoneNames.Side.values());
  private final Map<Tool, JToggleButton> toolButtons = new LinkedHashMap<>();
  private final JLabel toolHint = new JLabel();
  private final JProgressBar progressBar = new JProgressBar(0, 100);
  private final JButton cancelButton = new JButton(Messages.getString("BoneGrowTool.cancel"));
  private final JLabel statusLabel = new JLabel(" ");
  private final JPanel boneRows = GuiUtils.getVerticalBoxLayoutPanel();
  private final JButton undoButton = new JButton(Messages.getString("BoneGrowTool.undo"));
  private final JButton redoButton = new JButton(Messages.getString("BoneGrowTool.redo"));
  private final JButton wholeButton = new JButton(Messages.getString("BoneGrowTool.whole"));
  private final JCheckBox moreBox = new JCheckBox(Messages.getString("BoneGrowTool.more"));
  private final JPanel morePanel = GuiUtils.getVerticalBoxLayoutPanel();
  private final JCheckBox previewBox = new JCheckBox(Messages.getString("BoneGrowTool.preview"));
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
  private final JScrollPane rootPane = new JScrollPane();

  private final SeedPicker picker = new SeedPicker();
  private final SegExplorer explorer = new SegExplorer();
  private final Map<String, GrowSession> sessions = new HashMap<>();

  /** Sessions to write once the running write is done, with their CT. */
  private final Map<GrowSession, CtSource> toWrite = new LinkedHashMap<>();

  private final Runnable historyListener = this::refreshUndo;
  private Tool tool;
  private CtSource source;
  private SwingWorker<?, ?> worker;
  private SegWriteWorker writer;
  private View3d historyView;
  private View3d lastView;

  public BoneGrowTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 41);
    dockable.setTitleIcon(ResourceUtil.getIcon(OtherIcon.VIEW_3D));
    setDockableWidth(320);
    initGui();
    viewChanged();
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
    regionBox.setMaximumRowCount(12);
    boneBox.setMaximumRowCount(20);
    regionBox.setToolTipText(Messages.getString("BoneGrowTool.region.tip"));
    boneBox.setToolTipText(Messages.getString("BoneGrowTool.preset.tip"));
    sideBox.setToolTipText(Messages.getString("BoneGrowTool.side.tip"));
    regionBox.addActionListener(_ -> fillBones());
    boneBox.addActionListener(_ -> applyPreset());
    panel.add(row(Messages.getString("BoneGrowTool.region"), regionBox));
    panel.add(GuiUtils.boxVerticalStrut(3));
    panel.add(row(Messages.getString("BoneGrowTool.preset"), boneBox));
    panel.add(GuiUtils.boxVerticalStrut(3));
    panel.add(row(Messages.getString("BoneGrowTool.side"), sideBox));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    JPanel tools = GuiUtils.getFlowLayoutPanel();
    for (Tool t : Tool.values()) {
      JToggleButton b = new JToggleButton(Messages.getString(t.label));
      b.setToolTipText(Messages.getString(t.hint));
      b.addActionListener(_ -> chooseTool(b.isSelected() ? t : null));
      toolButtons.put(t, b);
      tools.add(b);
    }
    panel.add(tools);
    panel.add(GuiUtils.getFlowLayoutPanel(toolHint));
    progressBar.setStringPainted(true);
    cancelButton.addActionListener(_ -> cancel());
    panel.add(GuiUtils.getHorizontalBoxLayoutPanel(5, progressBar, cancelButton));
    panel.add(GuiUtils.getFlowLayoutPanel(statusLabel));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    JLabel listTitle = new JLabel(Messages.getString("BoneGrowTool.list"));
    listTitle.setFont(listTitle.getFont().deriveFont(Font.BOLD));
    panel.add(GuiUtils.getFlowLayoutPanel(listTitle));
    panel.add(boneRows);
    panel.add(GuiUtils.boxVerticalStrut(gap));

    undoButton.setToolTipText(Messages.getString("BoneGrowTool.undo.tip"));
    redoButton.setToolTipText(Messages.getString("BoneGrowTool.redo.tip"));
    wholeButton.setToolTipText(Messages.getString("BoneGrowTool.whole.tip"));
    undoButton.addActionListener(_ -> selectedView().ifPresent(View3d::undoEdit));
    redoButton.addActionListener(_ -> selectedView().ifPresent(View3d::redoEdit));
    wholeButton.addActionListener(_ -> selectedView().ifPresent(View3d::restoreVolume));
    panel.add(GuiUtils.getFlowLayoutPanel(undoButton, redoButton, wholeButton));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    moreBox.addActionListener(_ -> morePanel.setVisible(moreBox.isSelected()));
    panel.add(GuiUtils.getFlowLayoutPanel(moreBox));
    String rangeTip = Messages.getString("BoneGrowTool.range.tip");
    lowerSpinner.setToolTipText(rangeTip);
    upperSpinner.setToolTipText(rangeTip);
    lowerSpinner.addChangeListener(_ -> updatePreview());
    morePanel.add(row(Messages.getString("BoneGrowTool.lower"), lowerSpinner));
    previewBox.setToolTipText(Messages.getString("BoneGrowTool.preview.tip"));
    previewBox.addActionListener(_ -> updatePreview());
    morePanel.add(GuiUtils.getFlowLayoutPanel(previewBox));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
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
    morePanel.setVisible(false);
    panel.add(morePanel);
    panel.add(GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.note"))));
    add(panel);
    add(GuiUtils.boxYLastElement(3));
    regionBox.setSelectedIndex(0);
    fillBones();
    setRunning(false);
    showHint();
  }

  /** Lists the bones of the chosen region. */
  private void fillBones() {
    BoneNames.Region region = (BoneNames.Region) regionBox.getSelectedItem();
    boneBox.removeAllItems();
    if (region != null) {
      region.bones().forEach(boneBox::addItem);
    }
    if (boneBox.getItemCount() > 0) {
      boneBox.setSelectedIndex(0);
    }
    applyPreset();
  }

  private BoneNames.Name selectedName() {
    return (BoneNames.Name) boneBox.getSelectedItem();
  }

  private BonePreset selectedPreset() {
    BoneNames.Name name = selectedName();
    return name == null ? BonePreset.OTHER : name.preset();
  }

  /** Puts the settings of the chosen bone in every field; the side only for a paired bone. */
  private void applyPreset() {
    BoneNames.Name name = selectedName();
    sideBox.setEnabled(name != null && name.paired());
    BonePreset p = selectedPreset();
    lowerSpinner.setValue((int) p.lowerHu());
    upperSpinner.setValue((int) GrowOptions.DEFAULT_UPPER_HU);
    closingSpinner.setValue(p.closingRadius());
    separationSpinner.setValue(p.separationVoxels());
    fillBox.setSelected(true);
    minPieceSpinner.setValue((int) p.minPieceMm3());
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

  /**
   * The 3D view holding a volume: the selected view when it is one, else the last one selected, as
   * a click in an MPR view may take the selection away from the 3D view.
   */
  private Optional<View3d> selectedView() {
    if (EventManager.getInstance().getSelectedViewPane() instanceof View3d view
        && view.getVolTexture() != null) {
      lastView = view;
      return Optional.of(view);
    }
    View3d last = lastView;
    return last != null && last.getVolTexture() != null && last.isShowing()
        ? Optional.of(last)
        : Optional.empty();
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

  // ---- Tools ----

  /** Arms a click tool, or none; the tool stays on for many clicks until turned off. */
  private void chooseTool(Tool chosen) {
    tool = chosen;
    toolButtons.forEach((t, b) -> b.setSelected(t == chosen));
    if (chosen == null) {
      picker.disarm();
      selectedView().ifPresent(v -> v.setSeedMarker(null));
    } else {
      leaveClickTool();
      picker.arm(this::picked, this::status);
    }
    showHint();
  }

  /** Puts rotation back on the 3D view's left button when a click tool is there. */
  private static void leaveClickTool() {
    EventManager manager = EventManager.getInstance();
    String left = manager.getMouseActions().getAction(MouseActions.T_LEFT);
    if (CLICK_TOOLS.contains(left)) {
      manager.changeLeftMouseAction(ActionW.ROTATION.cmd());
    }
  }

  private void showHint() {
    toolHint.setText(
        html(Messages.getString(tool == null ? "BoneGrowTool.pickTool" : tool.hint), null));
  }

  /** A click of the armed tool, as a patient position. */
  private void picked(Seed seed) {
    if (tool == null) {
      return;
    }
    if (worker != null) {
      status(Messages.getString("BoneGrowTool.busy"));
      return;
    }
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      warn(Messages.getString("BoneGrowTool.noCt"));
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
      view.get().setSeedMarker(seed.lps());
      GrowSession session = session(ct);
      switch (tool) {
        case NEW -> startGrow(view.get(), ct, session, voxel);
        case ERASE -> startSplit(ct, session, voxel, SplitWorker.Kind.ERASE);
        case SPLIT -> startSplit(ct, session, voxel, SplitWorker.Kind.SPLIT);
      }
    } catch (Problem e) {
      warn(e.getMessage());
    }
  }

  private void startGrow(View3d view, CtSource ct, GrowSession session, int[] voxel)
      throws Problem {
    // A click on a coloured bone's surface may land a voxel outside it.
    Optional<Found> taken = session.boneNear(voxel, 1);
    if (taken.isPresent()) {
      warn(String.format(Messages.getString("BoneGrowTool.alreadyIn"), taken.get().bone().label()));
      return;
    }
    VoxelBox crop = cropBox.isSelected() ? ct.cropBox(view).orElse(null) : null;
    start(new BoneGrowWorker(ct, new Request(voxel, crop, options(ct)), session.masksExcept(-1)));
  }

  private void startSplit(CtSource ct, GrowSession session, int[] voxel, SplitWorker.Kind kind) {
    Optional<Found> found = session.boneNear(voxel, NEAR_BONE_VOXELS);
    if (found.isEmpty()) {
      warn(
          Messages.getString(
              session.bones().isEmpty() ? "BoneGrowTool.noBoneYet" : "BoneGrowTool.noBoneThere"));
      return;
    }
    start(new SplitWorker(kind, found.get().bone(), found.get().voxel()));
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

  private void start(SwingWorker<?, ?> w) {
    worker = w;
    worker.addPropertyChangeListener(this::workerChanged);
    status(
        Messages.getString(
            w instanceof SplitWorker s && s.kind() == SplitWorker.Kind.ERASE
                ? "BoneGrowTool.erasing"
                : w instanceof SplitWorker ? "BoneGrowTool.splitting" : "BoneGrowTool.growing"));
    setRunning(true);
    worker.execute();
  }

  private void cancel() {
    if (worker != null) {
      worker.cancel(true);
    }
  }

  private void setRunning(boolean running) {
    progressBar.setVisible(running);
    cancelButton.setVisible(running);
    progressBar.setIndeterminate(running && !(worker instanceof BoneGrowWorker));
    progressBar.setValue(0);
    revalidate();
  }

  private void workerChanged(PropertyChangeEvent evt) {
    switch (evt.getPropertyName()) {
      case "progress" -> progressBar.setValue((Integer) evt.getNewValue()); // NON-NLS
      case BoneGrowWorker.STAGE -> {
        String text = (String) evt.getNewValue();
        status(text);
        // The grow cannot know how far the region will reach.
        progressBar.setIndeterminate(Messages.getString("BoneGrowTool.stage.GROWING").equals(text));
      }
      case "state" -> { // NON-NLS
        if (evt.getNewValue() == StateValue.DONE && evt.getSource() == worker) {
          finished(worker);
        }
      }
      default -> {
        // other properties are not shown
      }
    }
  }

  private void finished(SwingWorker<?, ?> done) {
    worker = null;
    setRunning(false);
    try {
      if (done instanceof BoneGrowWorker grow) {
        grown(grow, grow.get());
      } else if (done instanceof SplitWorker split) {
        splitDone(split, split.get());
      }
    } catch (CancellationException e) {
      status(Messages.getString("BoneGrowTool.cancelled"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof Problem problem) {
        warn(problem.getMessage());
      } else if (e.getCause() instanceof SplitException split) {
        warn(
            Messages.getString(
                split.reason() == SplitException.Reason.NOT_IN_BONE
                    ? "BoneGrowTool.noBoneThere"
                    : "BoneGrowTool.notSeparable"));
      } else if (e.getCause() instanceof OutOfMemoryError) {
        LOGGER.error("Bones", e.getCause());
        warn(Messages.getString("BoneGrowTool.memory"));
      } else {
        LOGGER.error("Bones", e.getCause());
        warn(String.format(Messages.getString("BoneGrowTool.failed"), e.getCause()));
      }
    }
  }

  private void grown(BoneGrowWorker done, Result result) {
    CtSource ct = done.ct();
    GrowSession session = session(ct);
    // The bones may have changed during the grow (an undo): keep out the ones there now.
    BoxMask mask = BoxMask.of(result).minus(session.masksExcept(-1));
    if (mask == null) {
      warn(Messages.getString("BoneGrowTool.allTaken"));
      return;
    }
    int id = session.newId();
    BoneNames.Name chosen = selectedName();
    List<String> taken = session.bones().stream().map(Bone::label).toList();
    String name =
        chosen == null
            ? Messages.getString("bone.other") + " " + id
            : BoneNames.label(chosen, (BoneNames.Side) sideBox.getSelectedItem(), taken);
    Bone bone =
        new Bone(
            id,
            name,
            session.colorOf(id),
            mask,
            result.seed(),
            BoneGrowWorker.description(done.request().options(), result));
    apply(ct, session, session.with(bone));
    double cm3 = bone.volumeCm3(ct.grid().voxelVolumeMm3());
    StringBuilder text =
        new StringBuilder(String.format(Messages.getString("BoneGrowTool.done"), name, cm3));
    if (done.request().options().separationVoxels() > 0 && !result.separated()) {
      text.append(' ').append(Messages.getString("BoneGrowTool.notSeparated"));
    }
    if (cm3 > LARGE_CM3) {
      warn(text.append(' ').append(Messages.getString("BoneGrowTool.large")).toString());
    } else {
      status(text.toString());
    }
  }

  private void splitDone(SplitWorker done, Split split) {
    Optional<CtSource> ct = currentSource();
    Bone bone = done.bone();
    GrowSession session = ct.map(this::session).orElse(null);
    if (session == null || session.bone(bone.id()).filter(b -> b.mask() == bone.mask()).isEmpty()) {
      warn(Messages.getString("BoneGrowTool.changedMeanwhile"));
      return;
    }
    double voxelMm3 = ct.get().grid().voxelVolumeMm3();
    double cm3 = split.cut().count() * voxelMm3 / 1000.0;
    Bone kept = bone.withMask(split.kept());
    if (done.kind() == SplitWorker.Kind.ERASE) {
      apply(ct.get(), session, session.replaced(bone.id(), kept, null));
      status(String.format(Messages.getString("BoneGrowTool.erased"), cm3, bone.label()));
    } else {
      int id = session.newId();
      String name = String.format(Messages.getString("BoneGrowTool.pieceName"), bone.label(), id);
      Bone piece =
          new Bone(
              id,
              name,
              session.colorOf(id),
              split.cut(),
              done.voxel(),
              "Split off " + bone.label() + "; not reviewed"); // NON-NLS
      apply(ct.get(), session, session.replaced(bone.id(), kept, piece));
      status(String.format(Messages.getString("BoneGrowTool.splitDone"), bone.label(), name, cm3));
    }
  }

  // ---- The list and its undo history ----

  /**
   * Makes {@code after} the bones of the session as one undoable step of the 3D view, then shows
   * and writes them.
   */
  private void apply(CtSource ct, GrowSession session, List<Bone> after) {
    List<Bone> before = session.bones();
    session.set(after);
    selectedView()
        .ifPresent(
            v ->
                v.pushEdit(
                    new EditHistory.Edit() {
                      @Override
                      public boolean undo() {
                        session.set(before);
                        bonesChanged(ct, session);
                        return true;
                      }

                      @Override
                      public boolean redo() {
                        session.set(after);
                        bonesChanged(ct, session);
                        return true;
                      }
                    }));
    bonesChanged(ct, session);
  }

  private void bonesChanged(CtSource ct, GrowSession session) {
    refreshList();
    write(ct, session);
  }

  /** Writes the session's bones now, or once the running write is done. */
  private void write(CtSource ct, GrowSession session) {
    if (writer != null) {
      toWrite.put(session, ct);
      return;
    }
    writer = new SegWriteWorker(ct, session);
    writer.addPropertyChangeListener(
        evt -> {
          if ("state".equals(evt.getPropertyName()) // NON-NLS
              && evt.getNewValue() == StateValue.DONE) {
            written((SegWriteWorker) evt.getSource());
          }
        });
    writer.execute();
  }

  private void written(SegWriteWorker done) {
    writer = null;
    GrowSession session = done.session();
    try {
      SegWriteWorker.Outcome outcome = done.get();
      String oldSop = session.sopInstanceUID();
      session.written(done.bones(), done.version(), outcome.sopInstanceUID());
      explorer.replace(
          done.ct().series(),
          session.segSeriesUID(),
          oldSop,
          outcome.file(),
          outcome.sopInstanceUID());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException | CancellationException e) {
      LOGGER.error("Writing the bones segmentation", e);
      warn(String.format(Messages.getString("BoneGrowTool.failed"), e.getCause()));
    }
    if (!session.isWritten()) {
      toWrite.putIfAbsent(session, done.ct());
    }
    if (!toWrite.isEmpty()) {
      Map.Entry<GrowSession, CtSource> next = toWrite.entrySet().iterator().next();
      toWrite.remove(next.getKey());
      write(next.getValue(), next.getKey());
    }
    refreshList();
  }

  /** The rows of the bones of the shown CT. */
  private void refreshList() {
    boneRows.removeAll();
    Optional<CtSource> ct = currentSource();
    List<Bone> bones = ct.map(this::session).map(GrowSession::bones).orElse(List.of());
    if (bones.isEmpty()) {
      boneRows.add(
          GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.none"))));
    } else {
      GrowSession session = session(ct.get());
      double voxelMm3 = ct.get().grid().voxelVolumeMm3();
      for (Bone bone : bones) {
        boneRows.add(boneRow(ct.get(), session, bone, voxelMm3));
      }
    }
    boneRows.revalidate();
    boneRows.repaint();
  }

  private JPanel boneRow(CtSource ct, GrowSession session, Bone bone, double voxelMm3) {
    JButton colour = small(new JButton(new ColorIcon(bone.color())));
    colour.setToolTipText(Messages.getString("BoneGrowTool.colour.tip"));
    colour.addActionListener(_ -> recolour(ct, session, bone));
    JLabel name =
        new JLabel(
            String.format(
                Messages.getString("BoneGrowTool.row"), bone.label(), bone.volumeCm3(voxelMm3)));
    name.setToolTipText(Messages.getString("BoneGrowTool.rename.tip"));
    name.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              rename(ct, session, bone);
            }
          }
        });
    JPanel top = GuiUtils.getFlowLayoutPanel(colour, name);

    boolean shown = session.writtenNumber(bone) > 0;
    JButton only = small(new JButton(Messages.getString("BoneGrowTool.only")));
    only.setToolTipText(Messages.getString("BoneGrowTool.only.tip"));
    only.addActionListener(_ -> cutInView(session, bone, true));
    JButton hide = small(new JButton(Messages.getString("BoneGrowTool.hide")));
    hide.setToolTipText(Messages.getString("BoneGrowTool.hide.tip"));
    hide.addActionListener(_ -> cutInView(session, bone, false));
    only.setEnabled(shown);
    hide.setEnabled(shown);
    JButton delete = small(new JButton(Messages.getString("BoneGrowTool.delete")));
    delete.setToolTipText(Messages.getString("BoneGrowTool.delete.tip"));
    delete.addActionListener(
        _ -> {
          apply(ct, session, session.without(bone.id()));
          status(String.format(Messages.getString("BoneGrowTool.deleted"), bone.label()));
        });
    JPanel actions = GuiUtils.getFlowLayoutPanel(only, hide, delete);
    actions.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 0));

    JPanel row = GuiUtils.getVerticalBoxLayoutPanel(top, actions);
    row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY.darker()));
    return row;
  }

  private static <B extends AbstractButton> B small(B button) {
    button.setMargin(new Insets(1, 4, 1, 4));
    return button;
  }

  /** Shows the bone alone, or hides it, in the 3D picture: an undoable cut of the 3D view. */
  private void cutInView(GrowSession session, Bone bone, boolean keep) {
    Optional<View3d> view = selectedView();
    int number = session.writtenNumber(bone);
    if (view.isEmpty() || number == 0) {
      status(Messages.getString("BoneGrowTool.notWrittenYet"));
      return;
    }
    String sop = session.sopInstanceUID();
    view.get().cutSegment(seg -> Objects.equals(sop, SegExplorer.sopUID(seg)), number, keep);
  }

  /** Renames a bone: a name of the list, with its side, or any name typed in. */
  private void rename(CtSource ct, GrowSession session, Bone bone) {
    JComboBox<String> names = new JComboBox<>(BoneNames.labels().toArray(String[]::new));
    names.setEditable(true);
    names.setMaximumRowCount(20);
    names.setSelectedItem(bone.label());
    int answer =
        JOptionPane.showConfirmDialog(
            WinUtil.getParentWindow(this),
            new Object[] {Messages.getString("BoneGrowTool.rename"), names},
            BUTTON_NAME,
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE);
    Object chosen = names.getEditor().getItem();
    String label = chosen == null ? "" : chosen.toString().trim();
    if (answer == JOptionPane.OK_OPTION && !label.isEmpty() && !label.equals(bone.label())) {
      apply(ct, session, session.replaced(bone.id(), bone.withLabel(label), null));
    }
  }

  private void recolour(CtSource ct, GrowSession session, Bone bone) {
    Color chosen =
        JColorChooser.showDialog(WinUtil.getParentWindow(this), bone.label(), bone.color());
    if (chosen != null && !chosen.equals(bone.color())) {
      apply(ct, session, session.replaced(bone.id(), bone.withColor(chosen), null));
    }
  }

  /** Follows the undo history of the selected 3D view, for the Undo and Redo buttons. */
  private void followHistory() {
    View3d view = selectedView().orElse(null);
    if (view != historyView) {
      if (historyView != null) {
        historyView.getEditHistory().removeListener(historyListener);
      }
      historyView = view;
      if (view != null) {
        view.getEditHistory().addListener(historyListener);
      }
    }
    refreshUndo();
  }

  private void refreshUndo() {
    View3d view = historyView;
    undoButton.setEnabled(view != null && view.getEditHistory().canUndo());
    redoButton.setEnabled(view != null && view.getEditHistory().canRedo());
    wholeButton.setEnabled(view != null);
  }

  private void viewChanged() {
    followHistory();
    refreshList();
  }

  // ---- Messages ----

  private void status(String message) {
    statusLabel.setText(html(message, null));
  }

  private void warn(String message) {
    statusLabel.setText(html(message, "#d9822b")); // NON-NLS
  }

  /** Wrapped text of the panel's width, in a colour when given. */
  private static String html(String text, String colour) {
    String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    String style = colour == null ? "" : "color:" + colour + ";"; // NON-NLS
    return "<html><body style='width:230px;" + style + "'>" + escaped + "</body></html>"; // NON-NLS
  }

  @Override
  public void changingViewContentEvent(SeriesViewerEvent event) {
    EVENT e = event.getEventType();
    if (EVENT.SELECT_VIEW.equals(e) || EVENT.SELECT.equals(e)) {
      viewChanged();
    }
  }

  @Override
  protected void changeToolWindowAnchor(CLocation clocation) {
    // the layout does not depend on the side
  }

  /** A small filled square of the bone colour. */
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
