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
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.EnumMap;
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
import javax.swing.DefaultBoundedRangeModel;
import javax.swing.DefaultComboBoxModel;
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
import javax.swing.JToggleButton.ToggleButtonModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.SwingWorker.StateValue;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
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
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.editor.image.ViewerPlugin;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.View3DContainer;
import org.weasis.dicom.viewer3d.vr.EditHistory;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * The Bones panel, beside the 3D view and beside the MPR views: the bones selected on the CT as a
 * list, and click tools that work alike in the 3D view and in the MPR views of the CT.
 *
 * <ul>
 *   <li>New bone: a click on a bone grows it from there (region grow) and adds it to the list. The
 *       bones already selected act as walls, so no voxel belongs to two bones.
 *   <li>Erase piece: a click on an unwanted part of a bone (a leak into the table or a vessel) cuts
 *       it off at its narrowest link and erases it.
 *   <li>Split: a click on a part of a bone makes that part a bone of its own, cut off at the
 *       narrowest link (the femoral head grown with the acetabulum).
 *   <li>Hide in 3D: a click on a bone, on a slice or in 3D, takes it out of the 3D picture, cut at
 *       the joint from its neighbours (the scaphoid off the radius), without selecting it.
 * </ul>
 *
 * <p>Each bone of the list can be shown alone or hidden in the 3D picture, renamed, recoloured and
 * deleted. Every step goes into the undo history of the 3D view, with its cuts and crops, so Undo
 * (Ctrl+Z) walks back through all of them. The bones are written as one DICOM Segmentation of the
 * CT in the background after each step, so the segmentation display, smooth surfaces and the Bone
 * STL export work on them. The grow settings stay under More settings.
 *
 * <p>The 3D viewer and the 2D viewer (whose tools the MPR shares) each hold a panel. The bones, the
 * armed tool, the settings and the messages are held once, here, and every panel shows them: the
 * panels share their Swing models, and what is not a model is sent to each live panel.
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
    SPLIT("BoneGrowTool.split", "BoneGrowTool.split.hint"),
    HIDE("BoneGrowTool.hideTool", "BoneGrowTool.hideTool.hint");

    final String label;
    final String hint;

    Tool(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }
  }

  // ---- Shared by every panel ----

  private static final DefaultComboBoxModel<BoneNames.Region> REGION =
      new DefaultComboBoxModel<>(BoneNames.REGIONS.toArray(BoneNames.Region[]::new));
  private static final DefaultComboBoxModel<BoneNames.Name> BONE = new DefaultComboBoxModel<>();
  private static final DefaultComboBoxModel<BoneNames.Side> SIDE =
      new DefaultComboBoxModel<>(BoneNames.Side.values());
  private static final SpinnerNumberModel LOWER =
      new SpinnerNumberModel((int) GrowOptions.DEFAULT_LOWER_HU, -1024, 30_000, 10);
  private static final SpinnerNumberModel UPPER =
      new SpinnerNumberModel((int) GrowOptions.DEFAULT_UPPER_HU, -1024, 30_000, 10);
  private static final SpinnerNumberModel CLOSING =
      new SpinnerNumberModel(1, 0, GrowOptions.MAX_RADIUS, 1);
  private static final SpinnerNumberModel SEPARATION =
      new SpinnerNumberModel(0, 0, GrowOptions.MAX_RADIUS, 1);
  private static final SpinnerNumberModel MIN_PIECE = new SpinnerNumberModel(100, 0, 1_000_000, 50);
  private static final ToggleButtonModel FILL = new ToggleButtonModel();
  private static final ToggleButtonModel CROP = new ToggleButtonModel();
  private static final ToggleButtonModel PREVIEW = new ToggleButtonModel();
  private static final ToggleButtonModel MORE = new ToggleButtonModel();
  private static final Map<Tool, ToggleButtonModel> TOOLS = new EnumMap<>(Tool.class);
  private static final DefaultBoundedRangeModel PROGRESS =
      new DefaultBoundedRangeModel(0, 0, 0, 100);

  private static final List<WeakReference<BoneGrowTool>> PANELS = new ArrayList<>();
  private static final SeedPicker PICKER = new SeedPicker();
  private static final SegExplorer EXPLORER = new SegExplorer();
  private static final Map<String, GrowSession> SESSIONS = new HashMap<>();

  /** Sessions to write once the running write is done, with their CT. */
  private static final Map<GrowSession, CtSource> TO_WRITE = new LinkedHashMap<>();

  private static Tool tool;
  private static CtSource source;
  private static SwingWorker<?, ?> worker;
  private static SegWriteWorker writer;
  private static View3d lastView;
  private static String statusHtml = " ";
  private static boolean indeterminate;
  private static boolean previewShown;

  static {
    FILL.setSelected(true);
    CROP.setSelected(true);
    for (Tool t : Tool.values()) {
      ToggleButtonModel model = new ToggleButtonModel();
      // Once on the model: every panel's button shares it, so this runs once per click.
      model.addActionListener(_ -> chooseTool(model.isSelected() ? t : null));
      TOOLS.put(t, model);
    }
    // Filled before the listeners, so loading the class touches no view.
    fillBones();
    REGION.addListDataListener(onSelection(BoneGrowTool::fillBones));
    BONE.addListDataListener(onSelection(BoneGrowTool::applyPreset));
    LOWER.addChangeListener(_ -> updatePreview());
    PREVIEW.addActionListener(_ -> updatePreview());
  }

  /** Runs {@code action} when the selected item of a combo box model changes. */
  private static ListDataListener onSelection(Runnable action) {
    return new ListDataListener() {
      @Override
      public void contentsChanged(ListDataEvent e) {
        if (e.getIndex0() == -1 && e.getIndex1() == -1) {
          action.run();
        }
      }

      @Override
      public void intervalAdded(ListDataEvent e) {
        // the selection event follows
      }

      @Override
      public void intervalRemoved(ListDataEvent e) {
        // the selection event follows
      }
    };
  }

  // ---- This panel ----

  private final JComboBox<BoneNames.Side> sideBox = new JComboBox<>(SIDE);
  private final JLabel toolHint = new JLabel();
  private final JProgressBar progressBar = new JProgressBar(PROGRESS);
  private final JButton cancelButton = new JButton(Messages.getString("BoneGrowTool.cancel"));
  private final JLabel statusLabel = new JLabel(" ");
  private final JPanel boneRows = GuiUtils.getVerticalBoxLayoutPanel();
  private final JButton undoButton = new JButton(Messages.getString("BoneGrowTool.undo"));
  private final JButton redoButton = new JButton(Messages.getString("BoneGrowTool.redo"));
  private final JButton wholeButton = new JButton(Messages.getString("BoneGrowTool.whole"));
  private final JPanel morePanel = GuiUtils.getVerticalBoxLayoutPanel();
  private final JScrollPane rootPane = new JScrollPane();
  private final Runnable historyListener = this::refreshUndo;
  private View3d historyView;

  public BoneGrowTool() {
    super(BUTTON_NAME, Insertable.Type.TOOL, 41);
    dockable.setTitleIcon(ResourceUtil.getIcon(OtherIcon.VIEW_3D));
    setDockableWidth(320);
    initGui();
    PANELS.add(new WeakReference<>(this));
    showState();
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
    JComboBox<BoneNames.Region> regionBox = new JComboBox<>(REGION);
    JComboBox<BoneNames.Name> boneBox = new JComboBox<>(BONE);
    regionBox.setMaximumRowCount(12);
    boneBox.setMaximumRowCount(20);
    regionBox.setToolTipText(Messages.getString("BoneGrowTool.region.tip"));
    boneBox.setToolTipText(Messages.getString("BoneGrowTool.preset.tip"));
    sideBox.setToolTipText(Messages.getString("BoneGrowTool.side.tip"));
    panel.add(row(Messages.getString("BoneGrowTool.region"), regionBox));
    panel.add(GuiUtils.boxVerticalStrut(3));
    panel.add(row(Messages.getString("BoneGrowTool.preset"), boneBox));
    panel.add(GuiUtils.boxVerticalStrut(3));
    panel.add(row(Messages.getString("BoneGrowTool.side"), sideBox));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    JPanel tools = GuiUtils.getFlowLayoutPanel();
    for (Tool t : Tool.values()) {
      JToggleButton b = new JToggleButton(Messages.getString(t.label));
      b.setModel(TOOLS.get(t));
      b.setToolTipText(Messages.getString(t.hint));
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

    JCheckBox moreBox = checkBox("BoneGrowTool.more", MORE);
    MORE.addItemListener(_ -> morePanel.setVisible(MORE.isSelected()));
    panel.add(GuiUtils.getFlowLayoutPanel(moreBox));
    String rangeTip = Messages.getString("BoneGrowTool.range.tip");
    JSpinner lowerSpinner = new JSpinner(LOWER);
    JSpinner upperSpinner = new JSpinner(UPPER);
    lowerSpinner.setToolTipText(rangeTip);
    upperSpinner.setToolTipText(rangeTip);
    morePanel.add(row(Messages.getString("BoneGrowTool.lower"), lowerSpinner));
    JCheckBox previewBox = checkBox("BoneGrowTool.preview", PREVIEW);
    previewBox.setToolTipText(Messages.getString("BoneGrowTool.preview.tip"));
    morePanel.add(GuiUtils.getFlowLayoutPanel(previewBox));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    morePanel.add(row(Messages.getString("BoneGrowTool.upper"), upperSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    JSpinner closingSpinner = new JSpinner(CLOSING);
    closingSpinner.setToolTipText(Messages.getString("BoneGrowTool.closing.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.closing"), closingSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    JSpinner separationSpinner = new JSpinner(SEPARATION);
    separationSpinner.setToolTipText(Messages.getString("BoneGrowTool.separation.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.separation"), separationSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    morePanel.add(GuiUtils.getFlowLayoutPanel(checkBox("BoneGrowTool.fill", FILL)));
    JSpinner minPieceSpinner = new JSpinner(MIN_PIECE);
    minPieceSpinner.setToolTipText(Messages.getString("BoneGrowTool.minPiece.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.minPiece"), minPieceSpinner));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    morePanel.add(GuiUtils.getFlowLayoutPanel(checkBox("BoneGrowTool.crop", CROP)));
    morePanel.setVisible(MORE.isSelected());
    panel.add(morePanel);
    panel.add(GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.note"))));
    add(panel);
    add(GuiUtils.boxYLastElement(3));
  }

  private static JCheckBox checkBox(String key, ToggleButtonModel model) {
    JCheckBox box = new JCheckBox(Messages.getString(key));
    box.setModel(model);
    return box;
  }

  private static JPanel row(String title, JComponent field) {
    return GuiUtils.getHorizontalBoxLayoutPanel(5, new JLabel(title + StringUtil.COLON), field);
  }

  /** The live panels; the ones the garbage collector took are dropped. */
  private static List<BoneGrowTool> panels() {
    List<BoneGrowTool> live = new ArrayList<>();
    PANELS.removeIf(
        ref -> {
          BoneGrowTool p = ref.get();
          if (p == null) {
            return true;
          }
          live.add(p);
          return false;
        });
    return live;
  }

  /** Shows the shared state that is not a Swing model: hint, side, progress and message. */
  private void showState() {
    toolHint.setText(
        html(Messages.getString(tool == null ? "BoneGrowTool.pickTool" : tool.hint), null));
    BoneNames.Name name = selectedName();
    sideBox.setEnabled(name != null && name.paired());
    boolean running = worker != null;
    progressBar.setVisible(running);
    cancelButton.setVisible(running);
    progressBar.setIndeterminate(running && indeterminate);
    statusLabel.setText(statusHtml);
    revalidate();
  }

  private static void showStateEverywhere() {
    panels().forEach(BoneGrowTool::showState);
  }

  // ---- Bone and settings ----

  /** Lists the bones of the chosen region. */
  private static void fillBones() {
    BoneNames.Region region = (BoneNames.Region) REGION.getSelectedItem();
    BONE.removeAllElements();
    if (region != null) {
      region.bones().forEach(BONE::addElement);
    }
    if (BONE.getSize() > 0) {
      BONE.setSelectedItem(BONE.getElementAt(0));
    }
    applyPreset();
  }

  private static BoneNames.Name selectedName() {
    return (BoneNames.Name) BONE.getSelectedItem();
  }

  private static BonePreset selectedPreset() {
    BoneNames.Name name = selectedName();
    return name == null ? BonePreset.OTHER : name.preset();
  }

  /** Puts the settings of the chosen bone in every field; the side only for a paired bone. */
  private static void applyPreset() {
    BonePreset p = selectedPreset();
    LOWER.setValue((int) p.lowerHu());
    UPPER.setValue((int) GrowOptions.DEFAULT_UPPER_HU);
    CLOSING.setValue(p.closingRadius());
    SEPARATION.setValue(p.separationVoxels());
    FILL.setSelected(true);
    MIN_PIECE.setValue((int) p.minPieceMm3());
    showStateEverywhere();
  }

  /** Shows or ends the threshold preview on the 3D view. */
  private static void updatePreview() {
    if (!PREVIEW.isSelected() && !previewShown) {
      return;
    }
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      return;
    }
    previewShown = PREVIEW.isSelected();
    if (previewShown) {
      view.get().previewThreshold(((Number) LOWER.getValue()).doubleValue());
    } else if (view.get().isPreviewingThreshold()) {
      view.get().previewThreshold(null);
    }
  }

  /** The settings in the fields, which a bone fills and More settings lets one change. */
  private static GrowOptions options(CtSource ct) {
    int lower = ((Number) LOWER.getValue()).intValue();
    int upper = ((Number) UPPER.getValue()).intValue();
    double mm3 = ((Number) MIN_PIECE.getValue()).doubleValue();
    return GrowOptions.defaults()
        .withRange(Math.min(lower, upper), Math.max(lower, upper))
        .withClosingRadius(((Number) CLOSING.getValue()).intValue())
        .withSeparation(((Number) SEPARATION.getValue()).intValue())
        .withFillHoles(FILL.isSelected())
        .withMinPieceVoxels(Math.round(mm3 / ct.grid().voxelVolumeMm3()));
  }

  // ---- The 3D view and its CT ----

  /**
   * The 3D view holding a volume: the selected one; else the last one selected, as a click in an
   * MPR view takes the selection away from the 3D view; else any 3D view on screen, for a panel
   * beside the MPR when no 3D view was selected yet.
   */
  private static Optional<View3d> selectedView() {
    if (EventManager.getInstance().getSelectedViewPane() instanceof View3d view
        && view.getVolTexture() != null) {
      lastView = view;
      return Optional.of(view);
    }
    View3d last = lastView;
    if (last != null && last.getVolTexture() != null && last.isShowing()) {
      return Optional.of(last);
    }
    Optional<View3d> open = openView();
    open.ifPresent(v -> lastView = v);
    return open;
  }

  /** A 3D view on screen showing a volume, if any. */
  private static Optional<View3d> openView() {
    List<ViewerPlugin<?>> plugins = GuiUtils.getUICore().getViewerPlugins();
    List<ViewerPlugin<?>> copy;
    synchronized (plugins) {
      copy = List.copyOf(plugins);
    }
    for (ViewerPlugin<?> plugin : copy) {
      if (plugin instanceof View3DContainer container) {
        for (ViewCanvas<DicomImageElement> canvas : container.getImagePanels()) {
          if (canvas instanceof View3d view && view.getVolTexture() != null && view.isShowing()) {
            return Optional.of(view);
          }
        }
      }
    }
    return Optional.empty();
  }

  /** The CT of the view, read again only when the view shows another volume. */
  private static CtSource source(View3d view) throws Problem {
    if (source == null || source.volume() != view.getVolTexture().getVolume()) {
      source = CtSource.of(view);
    }
    return source;
  }

  private static Optional<CtSource> currentSource() {
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

  private static GrowSession session(CtSource ct) {
    return SESSIONS.computeIfAbsent(ct.seriesUID(), GrowSession::new);
  }

  // ---- Tools ----

  /** Arms a click tool, or none; the tool stays on for many clicks until turned off. */
  private static void chooseTool(Tool chosen) {
    tool = chosen;
    TOOLS.forEach((t, model) -> model.setSelected(t == chosen));
    if (chosen == null) {
      PICKER.disarm();
      selectedView().ifPresent(v -> v.setSeedMarker(null));
    } else {
      leaveClickTool();
      PICKER.arm(BoneGrowTool::picked, BoneGrowTool::status);
    }
    showStateEverywhere();
  }

  /** Puts rotation back on the 3D view's left button when a click tool is there. */
  private static void leaveClickTool() {
    EventManager manager = EventManager.getInstance();
    String left = manager.getMouseActions().getAction(MouseActions.T_LEFT);
    if (CLICK_TOOLS.contains(left)) {
      manager.changeLeftMouseAction(ActionW.ROTATION.cmd());
    }
  }

  /** A click of the armed tool, as a patient position. */
  private static void picked(Seed seed) {
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
        case HIDE -> {
          status(Messages.getString("BoneGrowTool.hiding"));
          view.get().cutStructureAt(seed.lps(), false, BoneGrowTool::status);
        }
      }
    } catch (Problem e) {
      warn(e.getMessage());
    }
  }

  private static void startGrow(View3d view, CtSource ct, GrowSession session, int[] voxel)
      throws Problem {
    // A click on a coloured bone's surface may land a voxel outside it.
    Optional<Found> taken = session.boneNear(voxel, 1);
    if (taken.isPresent()) {
      warn(String.format(Messages.getString("BoneGrowTool.alreadyIn"), taken.get().bone().label()));
      return;
    }
    VoxelBox crop = CROP.isSelected() ? ct.cropBox(view).orElse(null) : null;
    start(new BoneGrowWorker(ct, new Request(voxel, crop, options(ct)), session.masksExcept(-1)));
  }

  private static void startSplit(
      CtSource ct, GrowSession session, int[] voxel, SplitWorker.Kind kind) {
    Optional<Found> found = session.boneNear(voxel, NEAR_BONE_VOXELS);
    if (found.isEmpty()) {
      warn(
          Messages.getString(
              session.bones().isEmpty() ? "BoneGrowTool.noBoneYet" : "BoneGrowTool.noBoneThere"));
      return;
    }
    start(new SplitWorker(kind, found.get().bone(), found.get().voxel()));
  }

  private static void start(SwingWorker<?, ?> w) {
    worker = w;
    worker.addPropertyChangeListener(BoneGrowTool::workerChanged);
    indeterminate = !(w instanceof BoneGrowWorker);
    PROGRESS.setValue(0);
    status(
        Messages.getString(
            w instanceof SplitWorker s && s.kind() == SplitWorker.Kind.ERASE
                ? "BoneGrowTool.erasing"
                : w instanceof SplitWorker ? "BoneGrowTool.splitting" : "BoneGrowTool.growing"));
    worker.execute();
  }

  private static void cancel() {
    if (worker != null) {
      worker.cancel(true);
    }
  }

  private static void workerChanged(PropertyChangeEvent evt) {
    switch (evt.getPropertyName()) {
      case "progress" -> PROGRESS.setValue((Integer) evt.getNewValue()); // NON-NLS
      case BoneGrowWorker.STAGE -> {
        String text = (String) evt.getNewValue();
        // The grow cannot know how far the region will reach.
        indeterminate = Messages.getString("BoneGrowTool.stage.GROWING").equals(text);
        status(text);
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

  private static void finished(SwingWorker<?, ?> done) {
    worker = null;
    showStateEverywhere();
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

  private static void grown(BoneGrowWorker done, Result result) {
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
            : BoneNames.label(chosen, (BoneNames.Side) SIDE.getSelectedItem(), taken);
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

  private static void splitDone(SplitWorker done, Split split) {
    Optional<CtSource> ct = currentSource();
    Bone bone = done.bone();
    GrowSession session = ct.map(BoneGrowTool::session).orElse(null);
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
  private static void apply(CtSource ct, GrowSession session, List<Bone> after) {
    Optional<View3d> view = selectedView();
    if (view.isEmpty() || view.get().getVolTexture().getVolume() != ct.volume()) {
      // Without the 3D view of this CT the step could not be undone.
      warn(Messages.getString("BoneGrowTool.noCt"));
      return;
    }
    List<Bone> before = session.bones();
    session.set(after);
    view.get()
        .pushEdit(
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
            });
    bonesChanged(ct, session);
  }

  private static void bonesChanged(CtSource ct, GrowSession session) {
    refreshLists();
    write(ct, session);
  }

  /** Writes the session's bones now, or once the running write is done. */
  private static void write(CtSource ct, GrowSession session) {
    if (writer != null) {
      TO_WRITE.put(session, ct);
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

  private static void written(SegWriteWorker done) {
    writer = null;
    GrowSession session = done.session();
    try {
      SegWriteWorker.Outcome outcome = done.get();
      String oldSop = session.sopInstanceUID();
      session.written(done.bones(), done.version(), outcome.sopInstanceUID());
      EXPLORER.replace(
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
    // Written again only when the list changed during the write: a write that failed is not
    // retried until the next step, so a full disk does not loop.
    if (session.version() != done.version()) {
      TO_WRITE.putIfAbsent(session, done.ct());
    }
    if (!TO_WRITE.isEmpty()) {
      Map.Entry<GrowSession, CtSource> next = TO_WRITE.entrySet().iterator().next();
      TO_WRITE.remove(next.getKey());
      write(next.getValue(), next.getKey());
    }
    refreshLists();
  }

  private static void refreshLists() {
    panels().forEach(BoneGrowTool::refreshList);
  }

  /** The rows of the bones of the shown CT. */
  private void refreshList() {
    boneRows.removeAll();
    Optional<CtSource> ct = currentSource();
    List<Bone> bones = ct.map(BoneGrowTool::session).map(GrowSession::bones).orElse(List.of());
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
  private static void cutInView(GrowSession session, Bone bone, boolean keep) {
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
    // The bone may have changed (an erase finishing) or gone while the dialog was open.
    Optional<Bone> now = session.bone(bone.id());
    if (answer == JOptionPane.OK_OPTION
        && now.isPresent()
        && !label.isEmpty()
        && !label.equals(now.get().label())) {
      apply(ct, session, session.replaced(bone.id(), now.get().withLabel(label), null));
    }
  }

  private void recolour(CtSource ct, GrowSession session, Bone bone) {
    Color chosen =
        JColorChooser.showDialog(WinUtil.getParentWindow(this), bone.label(), bone.color());
    Optional<Bone> now = session.bone(bone.id());
    if (chosen != null && now.isPresent() && !chosen.equals(now.get().color())) {
      apply(ct, session, session.replaced(bone.id(), now.get().withColor(chosen), null));
    }
  }

  /** Follows the undo history of the 3D view, for the Undo and Redo buttons. */
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

  private static void status(String message) {
    statusHtml = html(message, null);
    showStateEverywhere();
  }

  private static void warn(String message) {
    statusHtml = html(message, "#d9822b"); // NON-NLS
    showStateEverywhere();
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
