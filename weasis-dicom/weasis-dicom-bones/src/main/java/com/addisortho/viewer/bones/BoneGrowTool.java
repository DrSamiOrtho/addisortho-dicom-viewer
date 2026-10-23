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
import com.addisortho.viewer.bones.grow.AutoSplit;
import com.addisortho.viewer.bones.grow.BonePreset;
import com.addisortho.viewer.bones.grow.BoneSplit.Split;
import com.addisortho.viewer.bones.grow.BoneSplit.SplitException;
import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.GrowOptions;
import com.addisortho.viewer.bones.grow.RegionGrow.Result;
import com.addisortho.viewer.bones.grow.VoxelBox;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
import java.util.function.Predicate;
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
import javax.swing.UIManager;
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
import org.weasis.dicom.viewer3d.dockable.SegmentationTool;
import org.weasis.dicom.viewer3d.vr.EditHistory;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * The Bones panel, beside the 3D view and beside the MPR views. When a CT opens in 3D and its bone
 * is isolated from the cast and the table, every bone is found at once and listed, each in its own
 * colour (see {@link AutoSplit}); the 3D view then shows the CT only inside the bones, tinted.
 *
 * <p>One way of working, alike in the 3D view and on the slices: click a bone to select it
 * (Shift, Ctrl or Cmd adds to the selection), then act on the selection:
 *
 * <ul>
 *   <li>Hide, Show only, Show all: what the 3D picture shows. A hidden bone stays in the list.
 *   <li>Join: the selected bones become one, such as a bone and the fragments the search kept
 *       apart.
 *   <li>Split: a click on the part that stays, then a click on the part to split off; the bone is
 *       cut at the narrowest neck between the two clicks (two bones the search left stuck).
 *   <li>Name: a name from the list of bones, or typed.
 * </ul>
 *
 * <p>Every step goes into the undo history of the 3D view, with its cuts and crops, so Undo
 * (Ctrl+Z) walks back through all of them. The bones are written as one DICOM Segmentation of the
 * CT in the background whenever their voxels, names or colours change, so the segmentation display,
 * smooth surfaces and the Bone STL export work on them; hiding, showing and selecting only change
 * the colour table of the 3D views. The older click tools (a bone grown from a click, a piece
 * erased) and the grow settings stay under More tools.
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

  /** Colour of the selected bones in the 3D picture. */
  private static final Color SELECTED_COLOR = new Color(255, 214, 0);

  /** The 3D tools a click sets off, which must not act on the clicks of the Bones tools. */
  private static final Set<String> CLICK_TOOLS =
      Set.of(
          ActionVol.KEEP_THIS.cmd(),
          ActionVol.REMOVE_THIS.cmd(),
          ActionVol.LENGTH.cmd(),
          ActionVol.ARROW.cmd(),
          ActionVol.SCALPEL.cmd());

  /** What a click on a bone does. */
  private enum Tool {
    /** Selects the bone; the tool on whenever no other is. */
    SELECT(null, "BoneGrowTool.pickTool"),
    SPLIT("BoneGrowTool.split", "BoneGrowTool.splitFirst"),
    NEW("BoneGrowTool.new", "BoneGrowTool.new.hint"),
    ERASE("BoneGrowTool.erase", "BoneGrowTool.erase.hint");

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
  private static final ToggleButtonModel NATURAL = new ToggleButtonModel();
  private static final Map<Tool, ToggleButtonModel> TOOLS = new EnumMap<>(Tool.class);
  private static final DefaultBoundedRangeModel PROGRESS =
      new DefaultBoundedRangeModel(0, 0, 0, 100);

  private static final List<WeakReference<BoneGrowTool>> PANELS = new ArrayList<>();
  private static final SeedPicker PICKER = new SeedPicker();
  private static final SegExplorer EXPLORER = new SegExplorer();
  private static final Map<String, GrowSession> SESSIONS = new HashMap<>();

  /** Sessions to write once the running write is done, with their CT. */
  private static final Map<GrowSession, CtSource> TO_WRITE = new LinkedHashMap<>();

  private static Tool tool = Tool.SELECT;
  private static CtSource source;
  private static SwingWorker<?, ?> worker;
  private static SegWriteWorker writer;
  private static View3d lastView;
  private static String statusHtml = " ";
  private static boolean indeterminate;
  private static boolean previewShown;

  /** The first click of Split: the bone and the voxel on the part that stays. */
  private static Found splitStart;

  static {
    FILL.setSelected(true);
    CROP.setSelected(true);
    for (Tool t : Tool.values()) {
      ToggleButtonModel model = new ToggleButtonModel();
      // Once on the model: every panel's button shares it, so this runs once per click.
      model.addActionListener(_ -> chooseTool(model.isSelected() ? t : Tool.SELECT));
      TOOLS.put(t, model);
    }
    // Filled before the listeners, so loading the class touches no view.
    fillBones();
    REGION.addListDataListener(onSelection(BoneGrowTool::fillBones));
    BONE.addListDataListener(onSelection(BoneGrowTool::applyPreset));
    LOWER.addChangeListener(_ -> updatePreview());
    PREVIEW.addActionListener(_ -> updatePreview());
    NATURAL.addActionListener(_ -> showDisplayType());
    View3d.addBoneIsolatedListener(BoneGrowTool::boneIsolated);
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
  private final JButton hideButton = small(new JButton(Messages.getString("BoneGrowTool.hide")));
  private final JButton onlyButton = small(new JButton(Messages.getString("BoneGrowTool.only")));
  private final JButton allButton = small(new JButton(Messages.getString("BoneGrowTool.showAll")));
  private final JButton joinButton = small(new JButton(Messages.getString("BoneGrowTool.join")));
  private final JButton nameButton = small(new JButton(Messages.getString("BoneGrowTool.name")));
  private final JButton deleteButton =
      small(new JButton(Messages.getString("BoneGrowTool.delete")));
  private final JToggleButton splitButton =
      small(new JToggleButton(Messages.getString("BoneGrowTool.split")));
  private final JButton findButton = new JButton(Messages.getString("BoneGrowTool.find"));
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
    if (!PICKER.isArmed()) {
      PICKER.arm(BoneGrowTool::picked, BoneGrowTool::missed);
    }
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

    findButton.setToolTipText(Messages.getString("BoneGrowTool.find.tip"));
    findButton.addActionListener(_ -> selectedView().ifPresent(BoneGrowTool::findBones));
    panel.add(GuiUtils.getFlowLayoutPanel(findButton));
    panel.add(GuiUtils.getFlowLayoutPanel(toolHint));
    progressBar.setStringPainted(true);
    cancelButton.addActionListener(_ -> cancel());
    panel.add(GuiUtils.getHorizontalBoxLayoutPanel(5, progressBar, cancelButton));
    panel.add(GuiUtils.getFlowLayoutPanel(statusLabel));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    hideButton.setToolTipText(Messages.getString("BoneGrowTool.hide.tip"));
    onlyButton.setToolTipText(Messages.getString("BoneGrowTool.only.tip"));
    allButton.setToolTipText(Messages.getString("BoneGrowTool.showAll.tip"));
    joinButton.setToolTipText(Messages.getString("BoneGrowTool.join.tip"));
    splitButton.setToolTipText(Messages.getString("BoneGrowTool.split.tip"));
    nameButton.setToolTipText(Messages.getString("BoneGrowTool.name.tip"));
    deleteButton.setToolTipText(Messages.getString("BoneGrowTool.delete.tip"));
    splitButton.setModel(TOOLS.get(Tool.SPLIT));
    hideButton.addActionListener(_ -> onSelected(BoneGrowTool::hideSelected));
    onlyButton.addActionListener(_ -> onSelected(BoneGrowTool::showOnlySelected));
    allButton.addActionListener(_ -> onSession(BoneGrowTool::showAll));
    joinButton.addActionListener(_ -> onSelected(BoneGrowTool::joinSelected));
    nameButton.addActionListener(_ -> onSelected(this::renameSelected));
    deleteButton.addActionListener(_ -> onSelected(BoneGrowTool::deleteSelected));
    panel.add(GuiUtils.getFlowLayoutPanel(hideButton, onlyButton, allButton));
    panel.add(GuiUtils.getFlowLayoutPanel(joinButton, splitButton, nameButton, deleteButton));
    JCheckBox naturalBox = checkBox("BoneGrowTool.natural", NATURAL);
    naturalBox.setToolTipText(Messages.getString("BoneGrowTool.natural.tip"));
    panel.add(GuiUtils.getFlowLayoutPanel(naturalBox));
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
    initMore(gap);
    morePanel.setVisible(MORE.isSelected());
    panel.add(morePanel);
    panel.add(GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.note"))));
    add(panel);
    add(GuiUtils.boxYLastElement(3));
  }

  /** More tools: a bone grown from a click, a piece erased, and the grow settings. */
  private void initMore(int gap) {
    morePanel.add(
        GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("BoneGrowTool.more.how"))));
    JComboBox<BoneNames.Region> regionBox = new JComboBox<>(REGION);
    JComboBox<BoneNames.Name> boneBox = new JComboBox<>(BONE);
    regionBox.setMaximumRowCount(12);
    boneBox.setMaximumRowCount(20);
    regionBox.setToolTipText(Messages.getString("BoneGrowTool.region.tip"));
    boneBox.setToolTipText(Messages.getString("BoneGrowTool.preset.tip"));
    sideBox.setToolTipText(Messages.getString("BoneGrowTool.side.tip"));
    morePanel.add(row(Messages.getString("BoneGrowTool.region"), regionBox));
    morePanel.add(GuiUtils.boxVerticalStrut(3));
    morePanel.add(row(Messages.getString("BoneGrowTool.preset"), boneBox));
    morePanel.add(GuiUtils.boxVerticalStrut(3));
    morePanel.add(row(Messages.getString("BoneGrowTool.side"), sideBox));
    morePanel.add(GuiUtils.boxVerticalStrut(gap));
    JPanel tools = GuiUtils.getFlowLayoutPanel();
    for (Tool t : List.of(Tool.NEW, Tool.ERASE)) {
      JToggleButton b = new JToggleButton(Messages.getString(t.label));
      b.setModel(TOOLS.get(t));
      b.setToolTipText(Messages.getString(t.hint));
      tools.add(b);
    }
    morePanel.add(tools);
    morePanel.add(GuiUtils.boxVerticalStrut(gap));

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

  /** Shows the shared state that is not a Swing model: hint, buttons, progress and message. */
  private void showState() {
    String hint =
        tool == Tool.SPLIT && splitStart != null ? "BoneGrowTool.splitSecond" : tool.hint;
    toolHint.setText(html(Messages.getString(hint), null));
    BoneNames.Name name = selectedName();
    sideBox.setEnabled(name != null && name.paired());
    boolean running = worker != null;
    progressBar.setVisible(running);
    cancelButton.setVisible(running);
    progressBar.setIndeterminate(running && indeterminate);
    statusLabel.setText(statusHtml);
    Optional<GrowSession> session = currentSource().map(BoneGrowTool::session);
    int bones = session.map(s -> s.bones().size()).orElse(0);
    int selected = session.map(s -> s.selectedBones().size()).orElse(0);
    findButton.setEnabled(!running && lastView != null);
    hideButton.setEnabled(selected > 0);
    onlyButton.setEnabled(selected > 0);
    allButton.setEnabled(bones > 0);
    joinButton.setEnabled(selected > 1);
    splitButton.setEnabled(bones > 0);
    nameButton.setEnabled(selected == 1);
    deleteButton.setEnabled(selected > 0);
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

  /** The settings in the fields, which a bone fills and More tools lets one change. */
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

  // ---- Finding the bones ----

  /**
   * After a view's bone is isolated (on opening a CT in 3D): finds the bones when the CT has none
   * yet; otherwise shows the ones it has, as in a 3D view built again on the same CT.
   */
  private static void boneIsolated(View3d view) {
    CtSource ct;
    try {
      ct = source(view);
    } catch (Problem e) {
      return; // not a CT the bones can be found on
    }
    lastView = view;
    GrowSession session = session(ct);
    if (session.bones().isEmpty()) {
      if (worker == null) {
        findBones(view);
      }
    } else if (view.getSegType() == SegmentationTool.Type.NONE) {
      view.setSegType(displayType());
    }
  }

  /** Finds every bone the 3D view shows; the list found replaces the one there, undoably. */
  private static void findBones(View3d view) {
    if (worker != null) {
      status(Messages.getString("BoneGrowTool.busy"));
      return;
    }
    if (view.isCutRunning()) {
      status(Messages.getString("BoneGrowTool.notWrittenYet"));
      return;
    }
    try {
      CtSource ct = source(view);
      start(new FindWorker(ct, ct.searchBox(view), ct.shown(view)));
    } catch (Problem e) {
      warn(e.getMessage());
    }
  }

  private static void found(FindWorker done, AutoSplit.Result result) {
    CtSource ct = done.ct();
    GrowSession session = session(ct);
    if (result.pieces().isEmpty()) {
      warn(Messages.getString("BoneGrowTool.foundNone"));
      return;
    }
    List<Bone> bones = new ArrayList<>();
    for (AutoSplit.Piece piece : result.pieces()) {
      int id = session.newId();
      bones.add(
          new Bone(
              id,
              String.format(Messages.getString("BoneGrowTool.autoName"), bones.size() + 1),
              session.colorOf(id),
              piece.mask(),
              piece.anchor(),
              "Found automatically; not reviewed")); // NON-NLS
    }
    session.selected().clear();
    apply(ct, session, bones);
    selectedView()
        .filter(v -> v.getVolTexture().getVolume() == ct.volume())
        .ifPresent(v -> v.setSegType(displayType()));
    String text = String.format(Messages.getString("BoneGrowTool.found"), bones.size());
    if (result.fragments() > 0) {
      String fragments = Messages.getString("BoneGrowTool.foundFragments");
      text += " " + String.format(fragments, result.fragments());
    }
    status(text);
  }

  /**
   * How the bones are shown in 3D: in their colours, or with the natural look of the CT (the plain
   * bone preset) inside the bones still shown. Hidden bones stay hidden either way.
   */
  static SegmentationTool.Type displayType() {
    return NATURAL.isSelected()
        ? SegmentationTool.Type.SEG_MASK_INCLUDE
        : SegmentationTool.Type.BONES;
  }

  /** Puts the chosen look on the 3D view and on the Segmentation panel's display choice. */
  private static void showDisplayType() {
    selectedView()
        .ifPresent(
            v -> {
              v.setSegType(displayType());
              EventManager.getInstance()
                  .getAction(ActionVol.SEG_TYPE)
                  .ifPresent(a -> a.setSelectedItemWithoutTriggerAction(displayType()));
            });
  }

  /** Ticks Natural look when the view shows the bones that way, as after the Segmentation panel. */
  private static void followDisplayType(View3d view) {
    if (view == null) {
      return;
    }
    SegmentationTool.Type type = view.getSegType();
    if (type == SegmentationTool.Type.BONES) {
      NATURAL.setSelected(false);
    } else if (type == SegmentationTool.Type.SEG_MASK_INCLUDE) {
      NATURAL.setSelected(true);
    }
  }

  // ---- Tools ----

  /** Arms a click tool; {@link Tool#SELECT} is on whenever no other one is. */
  private static void chooseTool(Tool chosen) {
    tool = chosen;
    splitStart = null;
    TOOLS.forEach((t, model) -> model.setSelected(t == chosen));
    if (chosen == Tool.SELECT) {
      selectedView().ifPresent(v -> v.setSeedMarker(null));
    } else {
      leaveClickTool();
    }
    showStateEverywhere();
  }

  /** Puts rotation back on the 3D view's left button when a click tool is there. */
  private static void leaveClickTool() {
    EventManager manager = EventManager.getInstance();
    if (CLICK_TOOLS.contains(leftTool())) {
      manager.changeLeftMouseAction(ActionW.ROTATION.cmd());
    }
  }

  private static String leftTool() {
    return EventManager.getInstance().getMouseActions().getAction(MouseActions.T_LEFT);
  }

  /** A click in the 3D view that met nothing. */
  private static void missed(String message) {
    if (tool == Tool.SELECT && !CLICK_TOOLS.contains(leftTool())) {
      currentSource().map(BoneGrowTool::session).ifPresent(s -> select(s, null, false));
    }
    status(message);
  }

  /** A click on a 3D or MPR view, as a patient position. */
  private static void picked(Seed seed) {
    if (tool == Tool.SELECT && CLICK_TOOLS.contains(leftTool())) {
      return; // the click is the 3D tool's: keep only this, remove this, a length...
    }
    if (worker != null) {
      status(Messages.getString("BoneGrowTool.busy"));
      return;
    }
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      if (tool != Tool.SELECT) {
        warn(Messages.getString("BoneGrowTool.noCt"));
      }
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
      GrowSession session = session(ct);
      LOGGER.debug(
          "Bones click {} in {} at voxel {} (adds {}): {}",
          tool,
          seed.plane(),
          java.util.Arrays.toString(voxel),
          seed.additive(),
          session.boneNear(voxel, NEAR_BONE_VOXELS).map(f -> f.bone().label()).orElse("no bone"));
      switch (tool) {
        case SELECT -> clickToSelect(session, voxel, seed.additive());
        case SPLIT -> clickToSplit(view.get(), ct, session, voxel, seed);
        case NEW -> {
          view.get().setSeedMarker(seed.lps());
          startGrow(view.get(), ct, session, voxel);
        }
        case ERASE -> {
          view.get().setSeedMarker(seed.lps());
          startErase(session, voxel);
        }
      }
    } catch (Problem e) {
      warn(e.getMessage());
    }
  }

  private static void clickToSelect(GrowSession session, int[] voxel, boolean additive) {
    Optional<Found> found = session.boneNear(voxel, NEAR_BONE_VOXELS);
    if (found.isEmpty()) {
      if (!session.bones().isEmpty()) {
        select(session, null, additive);
        status(Messages.getString("BoneGrowTool.nothingThere"));
      }
      return;
    }
    select(session, found.get().bone(), additive);
  }

  /**
   * Selects {@code bone} alone, or adds it to the selection (takes it out again when it was in);
   * {@code null} clears the selection unless {@code additive}.
   */
  private static void select(GrowSession session, Bone bone, boolean additive) {
    Set<Integer> selected = session.selected();
    if (bone == null) {
      if (additive || selected.isEmpty()) {
        return;
      }
      selected.clear();
    } else if (additive) {
      if (!selected.remove(bone.id())) {
        selected.add(bone.id());
      }
    } else {
      selected.clear();
      selected.add(bone.id());
    }
    List<Bone> now = session.selectedBones();
    if (now.size() == 1) {
      status(String.format(Messages.getString("BoneGrowTool.selected"), now.getFirst().label()));
    } else if (now.size() > 1) {
      status(String.format(Messages.getString("BoneGrowTool.selectedMany"), now.size()));
    } else {
      status(" ");
    }
    styleViews(session);
    refreshLists();
  }

  private static void clickToSplit(
      View3d view, CtSource ct, GrowSession session, int[] voxel, Seed seed) {
    Optional<Found> found = session.boneNear(voxel, NEAR_BONE_VOXELS);
    if (found.isEmpty()) {
      warn(
          Messages.getString(
              session.bones().isEmpty() ? "BoneGrowTool.noBoneYet" : "BoneGrowTool.noBoneThere"));
      return;
    }
    view.setSeedMarker(seed.lps());
    if (splitStart == null
        || session.bone(splitStart.bone().id()).filter(b -> b == splitStart.bone()).isEmpty()) {
      splitStart = found.get();
      select(session, found.get().bone(), false);
      showStateEverywhere();
      return;
    }
    if (found.get().bone().id() != splitStart.bone().id()) {
      // Another bone: it becomes the one to split, from this click.
      splitStart = found.get();
      select(session, found.get().bone(), false);
      status(Messages.getString("BoneGrowTool.splitOther"));
      return;
    }
    Found first = splitStart;
    splitStart = null;
    double[] mm = {ct.grid().columnSpacing(), ct.grid().rowSpacing(), ct.grid().sliceSpacing()};
    start(new SplitWorker(first.bone(), first.voxel(), found.get().voxel(), mm));
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

  private static void startErase(GrowSession session, int[] voxel) {
    Optional<Found> found = session.boneNear(voxel, NEAR_BONE_VOXELS);
    if (found.isEmpty()) {
      warn(
          Messages.getString(
              session.bones().isEmpty() ? "BoneGrowTool.noBoneYet" : "BoneGrowTool.noBoneThere"));
      return;
    }
    start(new SplitWorker(SplitWorker.Kind.ERASE, found.get().bone(), found.get().voxel()));
  }

  private static void start(SwingWorker<?, ?> w) {
    worker = w;
    worker.addPropertyChangeListener(BoneGrowTool::workerChanged);
    indeterminate = !(w instanceof BoneGrowWorker);
    PROGRESS.setValue(0);
    String message;
    if (w instanceof FindWorker) {
      message = "BoneGrowTool.finding";
    } else if (w instanceof SplitWorker s) {
      message =
          s.kind() == SplitWorker.Kind.ERASE ? "BoneGrowTool.erasing" : "BoneGrowTool.splitting";
    } else {
      message = "BoneGrowTool.growing";
    }
    status(Messages.getString(message));
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
      } else if (done instanceof FindWorker find) {
        found(find, find.get());
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
    selectedView().ifPresent(v -> v.setSeedMarker(null));
    double voxelMm3 = ct.get().grid().voxelVolumeMm3();
    double cm3 = split.cut().count() * voxelMm3 / 1000.0;
    Bone kept = session.bone(bone.id()).get().withMask(split.kept());
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
      session.selected().clear();
      session.selected().add(id);
      // One split per press of Split: the next click selects again.
      chooseTool(Tool.SELECT);
      styleViews(session);
      refreshLists();
      status(String.format(Messages.getString("BoneGrowTool.splitDone"), bone.label(), name, cm3));
    }
  }

  // ---- Acting on the selection ----

  /** Runs {@code action} on the selected bones of the shown CT, or asks for a selection. */
  private static void onSelected(SelectionAction action) {
    Optional<CtSource> ct = currentSource();
    if (ct.isEmpty()) {
      warn(Messages.getString("BoneGrowTool.noCt"));
      return;
    }
    GrowSession session = session(ct.get());
    List<Bone> selected = session.selectedBones();
    if (selected.isEmpty()) {
      status(Messages.getString("BoneGrowTool.selectFirst"));
      return;
    }
    action.run(ct.get(), session, selected);
  }

  private static void onSession(SelectionAction action) {
    currentSource()
        .ifPresent(
            ct -> {
              GrowSession session = session(ct);
              action.run(ct, session, session.selectedBones());
            });
  }

  @FunctionalInterface
  private interface SelectionAction {
    void run(CtSource ct, GrowSession session, List<Bone> selected);
  }

  private static void hideSelected(CtSource ct, GrowSession session, List<Bone> selected) {
    List<Integer> ids = selected.stream().map(Bone::id).toList();
    apply(ct, session, hidden(session, b -> ids.contains(b.id()) || b.hidden()));
    session.selected().clear();
    styleViews(session);
    refreshLists();
    String what = selected.size() == 1 ? selected.getFirst().label() : String.valueOf(ids.size());
    status(String.format(Messages.getString("BoneGrowTool.hidden"), what));
  }

  private static void showOnlySelected(CtSource ct, GrowSession session, List<Bone> selected) {
    List<Integer> ids = selected.stream().map(Bone::id).toList();
    apply(ct, session, hidden(session, b -> !ids.contains(b.id())));
  }

  private static void showAll(CtSource ct, GrowSession session, List<Bone> selected) {
    apply(ct, session, hidden(session, _ -> false));
  }

  /** The list with each bone hidden where {@code hide} says. */
  private static List<Bone> hidden(GrowSession session, Predicate<Bone> hide) {
    return session.bones().stream().map(b -> b.withHidden(hide.test(b))).toList();
  }

  /** The selected bones become one: the first in the list keeps its name and colour. */
  private static void joinSelected(CtSource ct, GrowSession session, List<Bone> selected) {
    if (selected.size() < 2) {
      status(Messages.getString("BoneGrowTool.joinTwo"));
      return;
    }
    Bone first = selected.getFirst();
    Bone joined =
        first.withMask(BoxMask.union(selected.stream().map(Bone::mask).toList())).withHidden(false);
    List<Bone> after = new ArrayList<>();
    for (Bone b : session.bones()) {
      if (b.id() == first.id()) {
        after.add(joined);
      } else if (selected.stream().noneMatch(s -> s.id() == b.id())) {
        after.add(b);
      }
    }
    session.selected().clear();
    session.selected().add(first.id());
    apply(ct, session, after);
    status(
        String.format(
            Messages.getString("BoneGrowTool.joined"), selected.size(), joined.label()));
  }

  private void renameSelected(CtSource ct, GrowSession session, List<Bone> selected) {
    if (selected.size() == 1) {
      rename(ct, session, selected.getFirst());
    }
  }

  private static void deleteSelected(CtSource ct, GrowSession session, List<Bone> selected) {
    List<Integer> ids = selected.stream().map(Bone::id).toList();
    apply(ct, session, session.bones().stream().filter(b -> !ids.contains(b.id())).toList());
    status(
        String.format(
            Messages.getString("BoneGrowTool.deleted"),
            selected.size() == 1 ? selected.getFirst().label() : String.valueOf(selected.size())));
  }

  // ---- The list and its undo history ----

  /**
   * Makes {@code after} the bones of the session as one undoable step of the 3D view, then shows
   * and, when their segments changed, writes them.
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
    styleViews(session);
    if (!session.isWritten()) {
      write(ct, session);
    }
    showStateEverywhere();
  }

  /**
   * Gives the 3D views the bones as they are shown: hidden ones taken out, selected ones in the
   * selection colour. Only the colour table of the views changes.
   */
  private static void styleViews(GrowSession session) {
    String sop = session.sopInstanceUID();
    if (sop == null) {
      return;
    }
    Map<Integer, View3d.SegStyle> styles = new HashMap<>();
    for (Bone bone : session.bones()) {
      int number = session.writtenNumber(bone);
      boolean selected = session.selected().contains(bone.id());
      if (number > 0 && (bone.hidden() || selected)) {
        styles.put(number, new View3d.SegStyle(!bone.hidden(), selected ? SELECTED_COLOR : null));
      }
    }
    View3d.setSegmentStyles(sop, styles);
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
      if (oldSop != null && !oldSop.equals(outcome.sopInstanceUID())) {
        View3d.setSegmentStyles(oldSop, Map.of());
      }
      // Set before the views load the new segmentation, so they show it styled at once.
      styleViews(session);
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
    showState();
  }

  /** A bone of the list: shown or not, its colour and name. A click on it selects it. */
  private JPanel boneRow(CtSource ct, GrowSession session, Bone bone, double voxelMm3) {
    JCheckBox shown = new JCheckBox();
    shown.setSelected(!bone.hidden());
    shown.setToolTipText(Messages.getString("BoneGrowTool.visible.tip"));
    shown.addActionListener(_ -> setHidden(ct, session, bone, !shown.isSelected()));
    JButton colour = small(new JButton(new ColorIcon(bone.color())));
    colour.setToolTipText(Messages.getString("BoneGrowTool.colour.tip"));
    colour.addActionListener(_ -> recolour(ct, session, bone));
    JLabel name =
        new JLabel(
            String.format(
                Messages.getString("BoneGrowTool.row"), bone.label(), bone.volumeCm3(voxelMm3)));
    name.setToolTipText(Messages.getString("BoneGrowTool.rename.tip"));
    JPanel row = GuiUtils.getFlowLayoutPanel(shown, colour, name);
    boolean selected = session.selected().contains(bone.id());
    if (selected) {
      row.setOpaque(true);
      row.setBackground(UIManager.getColor("List.selectionBackground")); // NON-NLS
      name.setForeground(UIManager.getColor("List.selectionForeground")); // NON-NLS
    }
    if (bone.hidden()) {
      name.setEnabled(false);
    }
    MouseAdapter click =
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              rename(ct, session, bone);
            } else {
              select(session, bone, e.isShiftDown() || e.isControlDown() || e.isMetaDown());
            }
          }
        };
    row.addMouseListener(click);
    name.addMouseListener(click);
    row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY.darker()));
    return row;
  }

  private static void setHidden(CtSource ct, GrowSession session, Bone bone, boolean hidden) {
    Optional<Bone> now = session.bone(bone.id());
    if (now.isPresent()) {
      apply(ct, session, session.replaced(bone.id(), now.get().withHidden(hidden), null));
    }
  }

  private static <B extends AbstractButton> B small(B button) {
    button.setMargin(new Insets(1, 4, 1, 4));
    return button;
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
    followDisplayType(selectedView().orElse(null));
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
