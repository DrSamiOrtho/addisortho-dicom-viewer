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
import bibliothek.gui.dock.common.mode.ExtendedMode;
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
import com.addisortho.viewer.bones.grow.MaskEdit;
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
 * <p>One way of working, alike in the 3D view and on the slices: click a bone to select it (Add and
 * Take out make every click add to the selection or take out of it; Shift, Ctrl or Cmd toggles
 * one), then act on the selection:
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

  /** Separate makes no part smaller than this, in mm³: two clicks on one spot make nothing. */
  private static final double MIN_PIECE_MM3 = 20;

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
    /** Every click adds the bone to the selection, no key held. */
    ADD("BoneGrowTool.add", "BoneGrowTool.add.hint"),
    /** Every click takes the bone out of the selection. */
    TAKE("BoneGrowTool.take", "BoneGrowTool.take.hint"),
    SPLIT("BoneGrowTool.split", "BoneGrowTool.splitFirst"),
    /** A brush: the bone-dense voxels around the click go into the bone. */
    PAINT("BoneGrowTool.paint", "BoneGrowTool.paint.hint"),
    /** A brush: the bone's voxels around the click are taken out of it. */
    RUB("BoneGrowTool.rub", "BoneGrowTool.rub.hint"),
    NEW("BoneGrowTool.new", "BoneGrowTool.new.hint"),
    ERASE("BoneGrowTool.erase", "BoneGrowTool.erase.hint");

    final String label;
    final String hint;

    Tool(String label, String hint) {
      this.label = label;
      this.hint = hint;
    }
  }

  /** What a click does to the selection. */
  private enum Pick {
    /** The bone alone. */
    ONE,
    /** Shift, Ctrl or Cmd held: in if it was out, out if it was in. */
    TOGGLE,
    ADD,
    TAKE
  }

  /** What a click on a bone does to the selection with the tool that is on. */
  private static Pick pickOf(boolean keyHeld) {
    return switch (tool) {
      case ADD -> Pick.ADD;
      case TAKE -> Pick.TAKE;
      default -> keyHeld ? Pick.TOGGLE : Pick.ONE;
    };
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

  /** Size of the brush of Add here and Take out here, mm. */
  private static final SpinnerNumberModel BRUSH = new SpinnerNumberModel(5, 1, 40, 1);

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
  private final JToggleButton addButton =
      small(new JToggleButton(Messages.getString("BoneGrowTool.add")));
  private final JToggleButton takeButton =
      small(new JToggleButton(Messages.getString("BoneGrowTool.take")));
  private final JToggleButton paintButton =
      small(new JToggleButton(Messages.getString("BoneGrowTool.paint")));
  private final JToggleButton rubButton =
      small(new JToggleButton(Messages.getString("BoneGrowTool.rub")));
  private final JButton smoothButton =
      small(new JButton(Messages.getString("BoneGrowTool.smooth")));
  private final JButton findButton = new JButton(Messages.getString("BoneGrowTool.find"));
  private final JButton undoButton = new JButton(Messages.getString("BoneGrowTool.undo"));
  private final JButton redoButton = new JButton(Messages.getString("BoneGrowTool.redo"));
  private final JButton wholeButton = new JButton(Messages.getString("BoneGrowTool.whole"));
  private final JPanel morePanel = GuiUtils.getVerticalBoxLayoutPanel();
  private final JScrollPane rootPane = new JScrollPane();
  private final Runnable historyListener = this::refreshUndo;
  private View3d historyView;

  public BoneGrowTool() {
    // Docked open beside the views, not a flap that slides away on the first click in a view:
    // the selection, the hints and the outcome of each step must stay in sight.
    super(BUTTON_NAME, POSITION.EAST, ExtendedMode.NORMALIZED, Insertable.Type.TOOL, 41);
    dockable.setTitleIcon(ResourceUtil.getIcon(OtherIcon.VIEW_3D));
    setDockableWidth(320);
    initGui();
    PANELS.add(new WeakReference<>(this));
    if (!PICKER.isArmed()) {
      PICKER.arm(
          BoneGrowTool::picked, BoneGrowTool::stroked, BoneGrowTool::isBrush, BoneGrowTool::missed);
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
    findButton.addActionListener(_ -> selectedView().ifPresent(BoneGrowTool::findOrShow));
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
    addButton.setModel(TOOLS.get(Tool.ADD));
    takeButton.setModel(TOOLS.get(Tool.TAKE));
    addButton.setToolTipText(Messages.getString("BoneGrowTool.add.tip"));
    takeButton.setToolTipText(Messages.getString("BoneGrowTool.take.tip"));
    paintButton.setModel(TOOLS.get(Tool.PAINT));
    rubButton.setModel(TOOLS.get(Tool.RUB));
    paintButton.setToolTipText(Messages.getString("BoneGrowTool.paint.tip"));
    rubButton.setToolTipText(Messages.getString("BoneGrowTool.rub.tip"));
    smoothButton.setToolTipText(Messages.getString("BoneGrowTool.smooth.tip"));
    smoothButton.addActionListener(_ -> onSelected(BoneGrowTool::smoothSelected));
    hideButton.addActionListener(_ -> onSelected(BoneGrowTool::hideSelected));
    onlyButton.addActionListener(_ -> onSelected(BoneGrowTool::showOnlySelected));
    allButton.addActionListener(_ -> onSession(BoneGrowTool::showAll));
    joinButton.addActionListener(_ -> onSelected(BoneGrowTool::joinSelected));
    nameButton.addActionListener(_ -> onSelected(this::renameSelected));
    deleteButton.addActionListener(_ -> onSelected(BoneGrowTool::deleteSelected));
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            new JLabel(Messages.getString("BoneGrowTool.selection")), addButton, takeButton));
    panel.add(GuiUtils.getFlowLayoutPanel(hideButton, onlyButton, allButton));
    panel.add(GuiUtils.getFlowLayoutPanel(joinButton, splitButton, nameButton, deleteButton));
    JSpinner brushSpinner = new JSpinner(BRUSH);
    brushSpinner.setToolTipText(Messages.getString("BoneGrowTool.brush.tip"));
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            new JLabel(Messages.getString("BoneGrowTool.edge")), paintButton, rubButton));
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            new JLabel(Messages.getString("BoneGrowTool.brush") + StringUtil.COLON),
            brushSpinner,
            smoothButton));
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
    String hint = tool == Tool.SPLIT && splitStart != null ? "BoneGrowTool.splitSecond" : tool.hint;
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
    boolean plain =
        bones > 0
            && selectedView().map(v -> v.getSegType() == SegmentationTool.Type.NONE).orElse(false);
    String findKey;
    if (plain) {
      findKey = "BoneGrowTool.showBones";
    } else {
      findKey = bones > 0 ? "BoneGrowTool.findAgain" : "BoneGrowTool.find";
    }
    findButton.setText(Messages.getString(findKey));
    hideButton.setEnabled(selected > 0);
    onlyButton.setEnabled(selected > 0);
    allButton.setEnabled(bones > 0);
    joinButton.setEnabled(selected > 1);
    splitButton.setEnabled(bones > 0);
    addButton.setEnabled(bones > 0);
    takeButton.setEnabled(bones > 0);
    paintButton.setEnabled(bones > 0);
    rubButton.setEnabled(bones > 0);
    smoothButton.setEnabled(selected > 0 && !running);
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
   * After a view's bone is isolated (on opening a CT in 3D): the 3D view stays the plain picture of
   * the bone; the bones are found and shown only when asked, with Find the bones.
   */
  private static void boneIsolated(View3d view) {
    try {
      source(view);
    } catch (Problem e) {
      return; // not a CT the bones can be found on
    }
    lastView = view;
    status(Messages.getString("BoneGrowTool.findHint"));
  }

  /** Find the bones, or show the bones already found when the 3D view shows the plain picture. */
  private static void findOrShow(View3d view) {
    if (bonesFound(view) && view.getSegType() == SegmentationTool.Type.NONE) {
      showBones(view);
    } else {
      findBones(view);
    }
  }

  private static boolean bonesFound(View3d view) {
    try {
      return !session(source(view)).bones().isEmpty();
    } catch (Problem e) {
      return false;
    }
  }

  /** Shows the bones on the 3D view, in colour or with the natural look, as chosen. */
  private static void showBones(View3d view) {
    view.setSegType(displayType());
    EventManager.getInstance()
        .getAction(ActionVol.SEG_TYPE)
        .ifPresent(a -> a.setSelectedItemWithoutTriggerAction(displayType()));
    showStateEverywhere();
  }

  /** Shows the bones when a step acts on them while the 3D view shows the plain picture. */
  private static void showBonesIfPlain() {
    selectedView()
        .filter(v -> v.getSegType() == SegmentationTool.Type.NONE && bonesFound(v))
        .ifPresent(BoneGrowTool::showBones);
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
    brushOnSlices(isBrush());
    showStateEverywhere();
  }

  /** Add here or Take out here is on: a drag on a slice paints a stroke. */
  private static boolean isBrush() {
    return tool == Tool.PAINT || tool == Tool.RUB;
  }

  /** The left button of the slices before the brush took it, or {@code null}. */
  private static String leftBeforeBrush;

  /**
   * While the brush is on, the slices' left button does nothing of its own, so a drag paints
   * without also changing the window or moving the image; the left button it had comes back after.
   * The MPR views and the 2D viewer share it; the 3D view keeps rotating.
   */
  private static void brushOnSlices(boolean on) {
    org.weasis.dicom.viewer2d.EventManager slices =
        org.weasis.dicom.viewer2d.EventManager.getInstance();
    String now = slices.getMouseActions().getAction(MouseActions.T_LEFT);
    String none = ActionW.NO_ACTION.cmd();
    if (on && leftBeforeBrush == null) {
      leftBeforeBrush = now;
      setSliceLeft(slices, none);
    } else if (!on && leftBeforeBrush != null) {
      String back = leftBeforeBrush;
      leftBeforeBrush = null;
      if (none.equals(now)) {
        setSliceLeft(slices, back);
      } // else the user chose another left tool meanwhile: keep it
    }
  }

  private static void setSliceLeft(org.weasis.dicom.viewer2d.EventManager slices, String command) {
    if (slices.getSelectedView2dContainer() != null) {
      slices.changeLeftMouseAction(command);
    } else {
      slices.getMouseActions().setAction(MouseActions.T_LEFT, command);
    }
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
      currentSource().map(BoneGrowTool::session).ifPresent(s -> select(s, null, Pick.ONE));
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
        case SELECT, ADD, TAKE -> clickToSelect(session, voxel, pickOf(seed.additive()));
        case PAINT ->
            paint(ct, session, voxel, MaskEdit.brush(voxel, brushRadius(ct), gridBox(ct)));
        case RUB -> rub(ct, session, voxel, MaskEdit.brush(voxel, brushRadius(ct), gridBox(ct)));
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

  /** A drag on a slice with the brush on: one Add here or Take out here along the whole stroke. */
  private static void stroked(List<Seed> seeds) {
    if (!isBrush() || seeds.isEmpty()) {
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
      List<int[]> voxels = new ArrayList<>();
      for (Seed seed : seeds) {
        if (seed.frameOfReferenceUID() != null
            && ct.frameOfReferenceUID() != null
            && !Objects.equals(seed.frameOfReferenceUID(), ct.frameOfReferenceUID())) {
          throw new Problem(Messages.getString("BoneGrowTool.otherFrame"));
        }
        ct.voxelOf(seed.lps()).ifPresent(voxels::add);
      }
      if (voxels.isEmpty()) {
        throw new Problem(Messages.getString("BoneGrowTool.seedOutside"));
      }
      GrowSession session = session(ct);
      BoxMask brush = MaskEdit.stroke(voxels, brushRadius(ct), gridBox(ct));
      LOGGER.debug("Bones stroke {} of {} points in {}", tool, voxels.size(), seeds.get(0).plane());
      if (tool == Tool.PAINT) {
        paint(ct, session, voxels.getFirst(), brush);
      } else {
        rub(ct, session, voxels.getFirst(), brush);
      }
    } catch (Problem e) {
      warn(e.getMessage());
    }
  }

  private static void clickToSelect(GrowSession session, int[] voxel, Pick pick) {
    Optional<Found> found = session.boneNear(voxel, NEAR_BONE_VOXELS);
    if (found.isEmpty()) {
      if (!session.bones().isEmpty()) {
        select(session, null, pick);
        status(Messages.getString("BoneGrowTool.nothingThere"));
      }
      return;
    }
    select(session, found.get().bone(), pick);
  }

  /**
   * Selects {@code bone} alone, adds it to the selection, takes it out, or toggles it, as {@code
   * pick} says; {@code null} clears the selection when the pick is {@link Pick#ONE}.
   */
  private static void select(GrowSession session, Bone bone, Pick pick) {
    Set<Integer> selected = session.selected();
    if (bone == null) {
      if (pick != Pick.ONE || selected.isEmpty()) {
        return;
      }
      selected.clear();
    } else {
      switch (pick) {
        case ONE -> {
          selected.clear();
          selected.add(bone.id());
        }
        case TOGGLE -> {
          if (!selected.remove(bone.id())) {
            selected.add(bone.id());
          }
        }
        case ADD -> selected.add(bone.id());
        case TAKE -> selected.remove(bone.id());
      }
    }
    showBonesIfPlain();
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

  // ---- The brush ----

  /** The brush as voxel radii per axis: its size in mm over the voxel spacing. */
  private static double[] brushRadius(CtSource ct) {
    double mm = ((Number) BRUSH.getValue()).doubleValue();
    return new double[] {
      mm / ct.grid().columnSpacing(), mm / ct.grid().rowSpacing(), mm / ct.grid().sliceSpacing()
    };
  }

  private static VoxelBox gridBox(CtSource ct) {
    return VoxelBox.of(ct.grid().columns(), ct.grid().rows(), ct.grid().sliceCount());
  }

  /** The bone a brush click works on: the one selected, else the one under the click. */
  private static Optional<Bone> brushTarget(GrowSession session, int[] voxel) {
    List<Bone> selected = session.selectedBones();
    if (selected.size() == 1) {
      return Optional.of(selected.getFirst());
    }
    if (selected.isEmpty()) {
      return session.boneNear(voxel, NEAR_BONE_VOXELS).map(Found::bone);
    }
    return Optional.empty();
  }

  /**
   * Add here: what looks like bone within the brush goes into the bone, out of any other bone it
   * was in.
   */
  private static void paint(CtSource ct, GrowSession session, int[] voxel, BoxMask brush) {
    Optional<Bone> target = brushTarget(session, voxel);
    if (target.isEmpty()) {
      status(Messages.getString("BoneGrowTool.brushWhich"));
      return;
    }
    Bone bone = target.get();
    GrowOptions o = options(ct);
    BoxMask dense =
        brush == null ? null : MaskEdit.dense(brush, ct.huSource(), o.lowerHu(), o.upperHu());
    if (dense == null) {
      warn(String.format(Messages.getString("BoneGrowTool.paintNothing"), o.lowerHu()));
      return;
    }
    BoxMask grown = MaskEdit.plus(bone.mask(), dense);
    if (grown == bone.mask()) {
      status(String.format(Messages.getString("BoneGrowTool.paintAlready"), bone.label()));
      return;
    }
    List<Bone> after = new ArrayList<>();
    for (Bone b : session.bones()) {
      if (b.id() == bone.id()) {
        after.add(b.withMask(grown));
        continue;
      }
      BoxMask left = b.mask().minus(List.of(dense));
      if (left != null) {
        after.add(left == b.mask() ? b : b.withMask(left));
      }
      // A bone wholly within the brush is painted into the target and leaves the list.
    }
    session.selected().clear();
    session.selected().add(bone.id());
    apply(ct, session, after);
    double mm3 = (grown.count() - bone.mask().count()) * ct.grid().voxelVolumeMm3();
    status(String.format(Messages.getString("BoneGrowTool.painted"), mm3, bone.label()));
  }

  /** Take out here: the bone's voxels within the brush leave it. */
  private static void rub(CtSource ct, GrowSession session, int[] voxel, BoxMask brush) {
    Optional<Bone> target = brushTarget(session, voxel);
    if (target.isEmpty()) {
      status(Messages.getString("BoneGrowTool.brushWhich"));
      return;
    }
    Bone bone = target.get();
    BoxMask left = brush == null ? bone.mask() : bone.mask().minus(List.of(brush));
    if (left == bone.mask()) {
      status(String.format(Messages.getString("BoneGrowTool.rubNothing"), bone.label()));
      return;
    }
    if (left == null) {
      warn(String.format(Messages.getString("BoneGrowTool.rubAll"), bone.label()));
      return;
    }
    session.selected().clear();
    session.selected().add(bone.id());
    apply(ct, session, session.replaced(bone.id(), bone.withMask(left), null));
    double mm3 = (bone.mask().count() - left.count()) * ct.grid().voxelVolumeMm3();
    status(String.format(Messages.getString("BoneGrowTool.rubbed"), mm3, bone.label()));
  }

  private static void smoothSelected(CtSource ct, GrowSession session, List<Bone> selected) {
    if (worker != null) {
      status(Messages.getString("BoneGrowTool.busy"));
      return;
    }
    start(new SmoothWorker(selected));
  }

  /** The smoothed bones replace the ones smoothed, kept out of the other bones. */
  private static void smoothed(SmoothWorker done, List<Bone> result) {
    Optional<CtSource> ct = currentSource();
    GrowSession session = ct.map(BoneGrowTool::session).orElse(null);
    if (session == null
        || done.bones().stream()
            .anyMatch(b -> session.bone(b.id()).filter(now -> now.mask() == b.mask()).isEmpty())) {
      warn(Messages.getString("BoneGrowTool.changedMeanwhile"));
      return;
    }
    List<Bone> after = new ArrayList<>(session.bones());
    double voxelMm3 = ct.get().grid().voxelVolumeMm3();
    double before = 0;
    double now = 0;
    for (Bone smooth : result) {
      BoxMask mask = smooth.mask().minus(session.masksExcept(smooth.id()));
      if (mask == null) {
        continue;
      }
      for (int i = 0; i < after.size(); i++) {
        if (after.get(i).id() == smooth.id()) {
          before += after.get(i).mask().count() * voxelMm3 / 1000.0;
          now += mask.count() * voxelMm3 / 1000.0;
          after.set(i, after.get(i).withMask(mask));
        }
      }
    }
    apply(ct.get(), session, after);
    if (result.size() == 1) {
      status(
          String.format(
              Messages.getString("BoneGrowTool.smoothed"), result.getFirst().label(), now, before));
    } else {
      status(String.format(Messages.getString("BoneGrowTool.smoothedMany"), result.size()));
    }
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
      // First click: the fragment.
      splitStart = found.get();
      select(session, found.get().bone(), Pick.ONE);
      String label = found.get().bone().label();
      status(String.format(Messages.getString("BoneGrowTool.splitFragment"), label));
      return;
    }
    // Second click: the bone the fragment comes away from, the same bone or another one.
    Found fragment = splitStart;
    splitStart = null;
    double[] mm = {ct.grid().columnSpacing(), ct.grid().rowSpacing(), ct.grid().sliceSpacing()};
    start(
        new SplitWorker(
            fragment.bone(),
            fragment.voxel(),
            found.get().bone(),
            found.get().voxel(),
            mm,
            ct.huSource()));
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
    } else if (w instanceof SmoothWorker) {
      message = "BoneGrowTool.smoothing";
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
      } else if (done instanceof SmoothWorker smooth) {
        smoothed(smooth, smooth.get());
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
    boolean unchanged =
        session != null
            && session.bone(bone.id()).filter(b -> b.mask() == bone.mask()).isPresent()
            && (done.neighbour() == null
                || session
                    .bone(done.neighbour().id())
                    .filter(b -> b.mask() == done.neighbour().mask())
                    .isPresent());
    if (!unchanged) {
      warn(Messages.getString("BoneGrowTool.changedMeanwhile"));
      return;
    }
    selectedView().ifPresent(v -> v.setSeedMarker(null));
    double voxelMm3 = ct.get().grid().voxelVolumeMm3();
    double cm3 = split.cut().count() * voxelMm3 / 1000.0;
    if (done.kind() == SplitWorker.Kind.ERASE) {
      Bone kept = session.bone(bone.id()).get().withMask(split.kept());
      apply(ct.get(), session, session.replaced(bone.id(), kept, null));
      status(String.format(Messages.getString("BoneGrowTool.erased"), cm3, bone.label()));
      return;
    }
    Bone neighbour = done.neighbour();
    double keptCm3 = split.kept().count() * voxelMm3 / 1000.0;
    if (Math.min(cm3, keptCm3) * 1000 < MIN_PIECE_MM3) {
      // Two clicks almost on one spot, or on one side of the fracture: nothing worth keeping.
      LOGGER.info(
          "Separate refused: {} cm3 against {} cm3", // NON-NLS
          String.format("%.3f", cm3), // NON-NLS
          String.format("%.3f", keptCm3)); // NON-NLS
      chooseTool(Tool.SELECT);
      warn(Messages.getString("BoneGrowTool.separateEmpty"));
      return;
    }
    int fragmentId;
    String fragmentName;
    List<Bone> after;
    if (neighbour.id() == bone.id()) {
      // One bone: the fragment becomes a bone of its own.
      fragmentId = session.newId();
      fragmentName =
          String.format(Messages.getString("BoneGrowTool.pieceName"), bone.label(), fragmentId);
      Bone piece =
          new Bone(
              fragmentId,
              fragmentName,
              session.colorOf(fragmentId),
              split.cut(),
              done.voxel(),
              "Separated from " + bone.label() + "; not reviewed"); // NON-NLS
      after = session.replaced(bone.id(), bone.withMask(split.kept()), piece);
    } else {
      // Two bones: their border is drawn again; each keeps its name and colour.
      fragmentId = bone.id();
      fragmentName = bone.label();
      List<Bone> list = new ArrayList<>();
      for (Bone b : session.bones()) {
        if (b.id() == bone.id()) {
          list.add(b.withMask(split.cut()));
        } else if (b.id() == neighbour.id()) {
          list.add(b.withMask(split.kept()));
        } else {
          list.add(b);
        }
      }
      after = list;
    }
    LOGGER.info(
        "Separate: {} is {} cm3, {} keeps {} cm3",
        fragmentName,
        String.format("%.2f", cm3), // NON-NLS
        neighbour.label(),
        String.format("%.2f", keptCm3)); // NON-NLS
    apply(ct.get(), session, after);
    session.selected().clear();
    session.selected().add(fragmentId);
    // One separation per press of Separate: the next click selects again.
    chooseTool(Tool.SELECT);
    styleViews(session);
    refreshLists();
    status(
        String.format(
            Messages.getString("BoneGrowTool.separated"),
            fragmentName,
            cm3,
            neighbour.label(),
            keptCm3));
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
        String.format(Messages.getString("BoneGrowTool.joined"), selected.size(), joined.label()));
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
    showBonesIfPlain();
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
              select(session, bone, pickOf(e.isShiftDown() || e.isControlDown() || e.isMetaDown()));
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
