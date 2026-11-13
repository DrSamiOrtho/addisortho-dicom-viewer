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
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.gui.util.WinUtil;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
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
import org.weasis.dicom.explorer.DicomModel;
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
    /** A line drawn across a bone on a slice cuts it in two, straight through. */
    CUT("BoneGrowTool.cut", "BoneGrowTool.cut.hint"),
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

  /** Width of the brush of Add here and Take out here, mm: the ring shown on the slice. */
  private static final SpinnerNumberModel BRUSH = new SpinnerNumberModel(6, 1, 60, 1);

  /** The brush works on the slice it is drawn on only, not as a ball through the slices. */
  private static final ToggleButtonModel THIN = new ToggleButtonModel();

  /** Add here takes only what looks like bone under the brush. */
  private static final ToggleButtonModel DENSE = new ToggleButtonModel();

  private static final ToggleButtonModel FILL = new ToggleButtonModel();
  private static final ToggleButtonModel CROP = new ToggleButtonModel();
  private static final ToggleButtonModel PREVIEW = new ToggleButtonModel();
  private static final ToggleButtonModel MORE = new ToggleButtonModel();
  private static final ToggleButtonModel NATURAL = new ToggleButtonModel();

  /** The bones are drawn as solid surfaces in their colours, not as the CT shows them. */
  private static final ToggleButtonModel SOLID = new ToggleButtonModel();

  private static final Map<Tool, ToggleButtonModel> TOOLS = new EnumMap<>(Tool.class);
  private static final DefaultBoundedRangeModel PROGRESS =
      new DefaultBoundedRangeModel(0, 0, 0, 100);

  private static final List<WeakReference<BoneGrowTool>> PANELS = new ArrayList<>();
  private static final SeedPicker PICKER = new SeedPicker();
  private static final SegExplorer EXPLORER = new SegExplorer();
  private static final BoneSessions<MediaSeries<DicomImageElement>> SESSIONS = new BoneSessions<>();

  /** The explorers already asked to tell when a CT is closed. */
  private static final Set<DicomModel> WATCHED =
      java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

  /** Sessions to write once the running write is done, with their CT. */
  private static final Map<GrowSession, CtSource> TO_WRITE = new LinkedHashMap<>();

  private static Tool tool = Tool.SELECT;
  private static CtSource source;
  private static SwingWorker<?, ?> worker;
  private static SegWriteWorker writer;
  private static View3d lastView;

  /** The 3D view of the CT whose slice was clicked last, while another 3D view is selected. */
  private static View3d sliceView;

  private static String statusHtml = " ";
  private static boolean indeterminate;
  private static boolean previewShown;

  /** The first click of Split: the bone and the voxel on the part that stays. */
  private static Found splitStart;

  static {
    FILL.setSelected(true);
    CROP.setSelected(true);
    DENSE.setSelected(true);
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
    NATURAL.addActionListener(
        _ -> {
          if (NATURAL.isSelected()) {
            SOLID.setSelected(false);
          }
          showDisplayType();
        });
    SOLID.addActionListener(
        _ -> {
          if (SOLID.isSelected()) {
            NATURAL.setSelected(false);
          }
          showDisplayType();
        });
    View3d.addBoneIsolatedListener(BoneGrowTool::boneIsolated);
    java.awt.Toolkit.getDefaultToolkit()
        .addAWTEventListener(BoneGrowTool::keyOnSlice, java.awt.AWTEvent.KEY_EVENT_MASK);
  }

  /** Takes back or does again the last step of the 3D view in use; says so when there is none. */
  private static void undoOrRedo(boolean redo) {
    putBack(endLiveStroke());
    Optional<View3d> view = selectedView();
    if (view.isEmpty()) {
      warn(Messages.getString("BoneGrowTool.noCt"));
      return;
    }
    if (view.get().isCutRunning()) {
      status(Messages.getString("BoneGrowTool.notWrittenYet"));
      return;
    }
    boolean done = redo ? view.get().redoEdit() : view.get().undoEdit();
    if (!done) {
      status(Messages.getString(redo ? "BoneGrowTool.noRedo" : "BoneGrowTool.noUndo"));
    }
  }

  /**
   * Ctrl+Z, Ctrl+Y and Ctrl+Shift+Z on a slice (an MPR view or the 2D viewer) undo and redo the
   * bone steps, as they do in the 3D view: the slices have no undo of their own.
   */
  private static void keyOnSlice(java.awt.AWTEvent event) {
    if (!(event instanceof java.awt.event.KeyEvent e)
        || e.getID() != java.awt.event.KeyEvent.KEY_PRESSED
        || e.isConsumed()
        || (e.getModifiersEx()
                & (java.awt.event.InputEvent.CTRL_DOWN_MASK
                    | java.awt.event.InputEvent.META_DOWN_MASK))
            == 0
        || (e.getKeyCode() != java.awt.event.KeyEvent.VK_Z
            && e.getKeyCode() != java.awt.event.KeyEvent.VK_Y)) {
      return;
    }
    Component c = e.getComponent();
    boolean onSlice =
        c instanceof org.weasis.dicom.viewer2d.View2d
            || javax.swing.SwingUtilities.getAncestorOfClass(
                    org.weasis.dicom.viewer2d.View2d.class, c)
                != null;
    if (!onSlice || currentSource().map(BoneGrowTool::session).filter(s -> hasSteps()).isEmpty()) {
      return;
    }
    e.consume();
    undoOrRedo(e.getKeyCode() == java.awt.event.KeyEvent.VK_Y || e.isShiftDown());
  }

  /** Whether the 3D view in use has a step to take back or do again. */
  private static boolean hasSteps() {
    return selectedView()
        .map(View3d::getEditHistory)
        .filter(h -> h.canUndo() || h.canRedo())
        .isPresent();
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
  private final JToggleButton cutButton =
      small(new JToggleButton(Messages.getString("BoneGrowTool.cut")));
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
          BoneGrowTool::picked,
          BoneGrowTool::stroked,
          BoneGrowTool::strokeGrows,
          BoneGrowTool::isBrushOnSlices,
          BoneGrowTool::ringMm,
          BoneGrowTool::missed);
    }
    showState();
    viewChanged();
    SelfCheck.start();
  }

  // ---- For the self-check of test builds ----

  static void selfFind(View3d view) {
    lastView = view;
    findBones(view);
  }

  static boolean selfIdle(View3d view) {
    return worker == null && bonesFound(view);
  }

  static void selfLook(boolean solid, boolean natural) {
    SOLID.setSelected(solid);
    NATURAL.setSelected(natural);
    showDisplayType();
  }

  static String selfStatus() {
    return statusHtml;
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
    cutButton.setModel(TOOLS.get(Tool.CUT));
    cutButton.setToolTipText(Messages.getString("BoneGrowTool.cut.tip"));
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
            new JLabel(Messages.getString("BoneGrowTool.edge")),
            paintButton,
            rubButton,
            cutButton));
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            new JLabel(Messages.getString("BoneGrowTool.brush") + StringUtil.COLON),
            brushSpinner,
            smoothButton));
    JCheckBox thinBox = checkBox("BoneGrowTool.thin", THIN);
    thinBox.setToolTipText(Messages.getString("BoneGrowTool.thin.tip"));
    JCheckBox denseBox = checkBox("BoneGrowTool.dense", DENSE);
    denseBox.setToolTipText(Messages.getString("BoneGrowTool.dense.tip"));
    panel.add(GuiUtils.getFlowLayoutPanel(thinBox, denseBox));
    JCheckBox naturalBox = checkBox("BoneGrowTool.natural", NATURAL);
    naturalBox.setToolTipText(Messages.getString("BoneGrowTool.natural.tip"));
    JCheckBox solidBox = checkBox("BoneGrowTool.solid", SOLID);
    solidBox.setToolTipText(Messages.getString("BoneGrowTool.solid.tip"));
    panel.add(GuiUtils.getFlowLayoutPanel(solidBox, naturalBox));
    panel.add(GuiUtils.boxVerticalStrut(gap));

    JLabel listTitle = new JLabel(Messages.getString("BoneGrowTool.list"));
    listTitle.setFont(listTitle.getFont().deriveFont(Font.BOLD));
    panel.add(GuiUtils.getFlowLayoutPanel(listTitle));
    panel.add(boneRows);
    panel.add(GuiUtils.boxVerticalStrut(gap));

    undoButton.setToolTipText(Messages.getString("BoneGrowTool.undo.tip"));
    redoButton.setToolTipText(Messages.getString("BoneGrowTool.redo.tip"));
    wholeButton.setToolTipText(Messages.getString("BoneGrowTool.whole.tip"));
    undoButton.addActionListener(_ -> undoOrRedo(false));
    redoButton.addActionListener(_ -> undoOrRedo(true));
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
    cutButton.setEnabled(bones > 0);
    smoothButton.setEnabled(selected > 0 && !running);
    nameButton.setEnabled(selected == 1);
    deleteButton.setEnabled(selected > 0);
    followHistory();
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
    View3d ofSlice = sliceView;
    if (ofSlice != null) {
      if (ofSlice.getVolTexture() != null && open3dViews().contains(ofSlice)) {
        lastView = ofSlice;
        return Optional.of(ofSlice);
      }
      sliceView = null;
    }
    if (EventManager.getInstance().getSelectedViewPane() instanceof View3d view
        && view.getVolTexture() != null) {
      lastView = view;
      return Optional.of(view);
    }
    View3d last = lastView;
    if (last != null && last.getVolTexture() != null && open3dViews().contains(last)) {
      // Also when the MPR lies over it as a tab: the bones and their undo history are its own.
      return Optional.of(last);
    }
    Optional<View3d> open = openView();
    open.ifPresent(v -> lastView = v);
    return open;
  }

  /** A 3D view holding a volume, if any: one on screen before one in a tab behind. */
  private static Optional<View3d> openView() {
    List<View3d> views = open3dViews();
    return views.stream()
        .filter(View3d::isShowing)
        .findFirst()
        .or(() -> views.stream().findFirst());
  }

  /** The 3D views of the open viewers that hold a volume, on screen or in a tab behind. */
  private static List<View3d> open3dViews() {
    List<ViewerPlugin<?>> plugins = GuiUtils.getUICore().getViewerPlugins();
    List<ViewerPlugin<?>> copy;
    synchronized (plugins) {
      copy = List.copyOf(plugins);
    }
    List<View3d> views = new ArrayList<>();
    for (ViewerPlugin<?> plugin : copy) {
      if (plugin instanceof View3DContainer container) {
        for (ViewCanvas<DicomImageElement> canvas : container.getImagePanels()) {
          if (canvas instanceof View3d view && view.getVolTexture() != null) {
            views.add(view);
          }
        }
      }
    }
    return views;
  }

  /**
   * Works on the 3D view of the CT a slice was clicked on, when several CTs are open in 3D and the
   * one in use is another.
   */
  private static void followSlice(Seed seed) {
    String frame = seed.frameOfReferenceUID();
    if (frame == null || "3D".equals(seed.plane())) { // NON-NLS
      return;
    }
    Optional<View3d> now = selectedView();
    if (now.isPresent() && frame.equals(frameOf(now.get()))) {
      return;
    }
    open3dViews().stream()
        .filter(v -> frame.equals(frameOf(v)))
        .findFirst()
        .ifPresent(
            v -> {
              sliceView = v;
              panels().forEach(BoneGrowTool::viewChanged);
            });
  }

  private static String frameOf(View3d view) {
    return SeedPicker.frameOfReferenceOf(view.getVolTexture().getVolume());
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
    watch(ct.series());
    GrowSession session = SESSIONS.of(ct.seriesUID(), ct.series());
    session.voxelSize(ct.grid().columnSpacing(), ct.grid().rowSpacing(), ct.grid().sliceSpacing());
    return session;
  }

  // ---- Letting go of a closed CT ----

  /**
   * Has the explorer of the series tell when something is closed. The explorer announces a removal
   * before it takes the series out, so the sessions are looked through once it is done.
   */
  private static void watch(MediaSeries<DicomImageElement> series) {
    if (series.getTagValue(TagW.ExplorerModel) instanceof DicomModel model && WATCHED.add(model)) {
      model.addPropertyChangeListener(
          evt -> {
            if (evt instanceof ObservableEvent e
                && e.getActionCommand() == ObservableEvent.BasicAction.REMOVE) {
              javax.swing.SwingUtilities.invokeLater(BoneGrowTool::releaseClosed);
            }
          });
    }
  }

  /** Whether the series has left its explorer: its CT is closed. */
  private static boolean isClosed(MediaSeries<DicomImageElement> series) {
    return series.getTagValue(TagW.ExplorerModel) instanceof DicomModel model
        && model.getParent(series, DicomModel.patient) == null;
  }

  /**
   * Frees the bones of every CT that was closed: the session with its masks and history, the label
   * volume the views drew them from, and a write still waiting. The DICOM SEG files already written
   * stay (review of 11 Nov 2026, B7: none of this was ever let go).
   */
  private static void releaseClosed() {
    List<GrowSession> closed = SESSIONS.releaseIf(BoneGrowTool::isClosed);
    for (GrowSession session : closed) {
      LiveBones.release(session);
      WRITE_LATER.remove(session);
      TO_WRITE.remove(session);
      if (session.sopInstanceUID() != null) {
        View3d.setSegmentStyles(session.sopInstanceUID(), Map.of());
      }
    }
    if (source != null && isClosed(source.series())) {
      source = null; // it holds the CT's volume
      splitStart = null;
    }
    View3d last = lastView;
    if (last != null && last.getVolTexture() == null) {
      lastView = null;
    }
    if (!closed.isEmpty()) {
      LOGGER.info("Bones of {} closed CT(s) released", closed.size());
      refreshLists();
    }
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
    look(view);
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
    List<Color> colours = done.colours();
    for (AutoSplit.Piece piece : result.pieces()) {
      int id = session.newId();
      bones.add(
          new Bone(
              id,
              String.format(Messages.getString("BoneGrowTool.autoName"), bones.size() + 1),
              colours.get(bones.size()),
              piece.mask(),
              piece.anchor(),
              "Found automatically; not reviewed")); // NON-NLS
    }
    session.selected().clear();
    if (!apply(ct, session, bones)) {
      return; // refused and said why: not followed by a message of success
    }
    selectedView()
        .filter(v -> v.getVolTexture().getVolume() == ct.volume())
        .ifPresent(BoneGrowTool::look);
    String text = String.format(Messages.getString("BoneGrowTool.found"), bones.size());
    if (result.fragments() > 0) {
      String fragments = Messages.getString("BoneGrowTool.foundFragments");
      text += " " + String.format(fragments, result.fragments());
    }
    if (result.jointsSkipped()) {
      // Said in the warning colour: bones may be missing from the list for it.
      warn(text + " " + Messages.getString("BoneGrowTool.foundJointsSkipped"));
      return;
    }
    status(text);
  }

  /**
   * How the bones are shown in 3D: in their colours, or with the natural look of the CT (the plain
   * bone preset) inside the bones still shown. Hidden bones stay hidden either way.
   */
  static SegmentationTool.Type displayType() {
    if (SOLID.isSelected()) {
      return SegmentationTool.Type.SEG_ONLY;
    }
    return NATURAL.isSelected()
        ? SegmentationTool.Type.SEG_MASK_INCLUDE
        : SegmentationTool.Type.BONES;
  }

  /** Puts the chosen look on a view: the display type, and solid bones when chosen. */
  private static void look(View3d view) {
    view.setSegSolid(SOLID.isSelected());
    view.setSegType(displayType());
  }

  /** Puts the chosen look on the 3D view and on the Segmentation panel's display choice. */
  private static void showDisplayType() {
    selectedView()
        .ifPresent(
            v -> {
              look(v);
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
      SOLID.setSelected(false);
    } else if (type == SegmentationTool.Type.SEG_MASK_INCLUDE) {
      NATURAL.setSelected(true);
      SOLID.setSelected(false);
    } else if (type == SegmentationTool.Type.SEG_ONLY && view.isSegSolid()) {
      SOLID.setSelected(true);
      NATURAL.setSelected(false);
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
    if (ringMm() <= 0) {
      PICKER.hideRing();
    }
    showStateEverywhere();
  }

  /** A tool drawn on the slices is on: Add here, Take out here or Cut line. */
  private static boolean isBrush() {
    return tool == Tool.PAINT || tool == Tool.RUB || tool == Tool.CUT;
  }

  /** Radius of the ring shown under the mouse on the slices, in mm; 0 when no brush paints. */
  private static double ringMm() {
    return (tool == Tool.PAINT || tool == Tool.RUB) && isBrushOnSlices() ? brushMm() : 0;
  }

  /** Radius of the brush, in mm. */
  private static double brushMm() {
    return ((Number) BRUSH.getValue()).doubleValue() / 2;
  }

  /**
   * The brush is on and holds the slices' left button: a click or drag on a slice paints. Once the
   * user picks another left tool (window, pan...), the slices do that instead, not both.
   */
  private static boolean isBrushOnSlices() {
    return isBrush()
        && ActionW.NO_ACTION
            .cmd()
            .equals(
                org.weasis.dicom.viewer2d.EventManager.getInstance()
                    .getMouseActions()
                    .getAction(MouseActions.T_LEFT));
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
    LiveStroke shown = endLiveStroke();
    try {
      pickedNow(seed);
    } finally {
      putBack(shown);
    }
  }

  private static void pickedNow(Seed seed) {
    followSlice(seed);
    if (tool == Tool.SELECT && CLICK_TOOLS.contains(leftTool())) {
      return; // the click is the 3D tool's: keep only this, remove this, a length...
    }
    if (isBrush() && !"3D".equals(seed.plane()) && !isBrushOnSlices()) { // NON-NLS
      return; // another left tool holds the slices: the click is that tool's
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
          session.boneNearClick(voxel).map(f -> f.bone().label()).orElse("no bone"));
      switch (tool) {
        case SELECT, ADD, TAKE -> clickToSelect(session, voxel, pickOf(seed.additive()));
        case PAINT, RUB -> {
          if (seed.additive()) {
            // Shift, Ctrl or Cmd with the brush on: the click chooses the bone it works on.
            clickToSelect(session, voxel, Pick.ONE);
          } else if (tool == Tool.PAINT) {
            paint(ct, session, voxel, brushOf(ct, List.of(voxel), seed));
          } else {
            rub(ct, session, voxel, brushOf(ct, List.of(voxel), seed));
          }
        }
        case CUT -> status(Messages.getString("BoneGrowTool.cut.hint"));
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

  // ---- A stroke shown as it is drawn ----

  /** How often a stroke being drawn is shown, in ms. */
  private static final int STROKE_SHOW_MS = 40;

  /**
   * A stroke being drawn on a slice: the bones as they were before it and the bone it works on.
   * While the mouse is down the bones are shown with the stroke so far, worked out afresh from
   * {@code before} each time; on release they are put back and the whole stroke is applied as one
   * step, so Undo and the written file see one change.
   */
  private record LiveStroke(CtSource ct, GrowSession session, List<Bone> before, Bone bone) {}

  private static LiveStroke liveStroke;
  private static List<Seed> strokeSoFar;

  private static final javax.swing.Timer STROKE_TIMER =
      new javax.swing.Timer(STROKE_SHOW_MS, _ -> showStroke());

  static {
    STROKE_TIMER.setRepeats(false);
  }

  /** A drag gained a point: the stroke is shown at the next tick, however many points came. */
  private static void strokeGrows(List<Seed> seeds) {
    if (!isBrushOnSlices() || worker != null) {
      return;
    }
    strokeSoFar = seeds;
    if (!STROKE_TIMER.isRunning()) {
      STROKE_TIMER.start();
    }
  }

  private static void showStroke() {
    List<Seed> seeds = strokeSoFar;
    if (seeds == null || seeds.isEmpty() || worker != null) {
      return;
    }
    try {
      LiveStroke s = liveStroke;
      if (s == null) {
        Optional<View3d> view = selectedView();
        if (view.isEmpty()) {
          return;
        }
        CtSource ct = source(view.get());
        GrowSession session = session(ct);
        List<int[]> voxels = strokeVoxels(ct, seeds);
        Optional<Bone> target;
        if (voxels.isEmpty()) {
          target = Optional.empty();
        } else if (tool == Tool.CUT) {
          target = cutTarget(session, voxels);
        } else {
          target = brushTarget(ct, session, voxels.getFirst());
        }
        if (target.isEmpty()) {
          return; // said on release
        }
        s = new LiveStroke(ct, session, session.bones(), target.get());
        liveStroke = s;
      }
      List<int[]> voxels = strokeVoxels(s.ct(), seeds);
      BoxMask brush;
      if (voxels.isEmpty()) {
        brush = null;
      } else if (tool == Tool.CUT) {
        brush = cutLine(s.ct(), voxels, seeds.getFirst());
      } else {
        brush = brushOf(s.ct(), voxels, seeds.getFirst());
      }
      List<Bone> after =
          brush == null
              ? null
              : tool == Tool.PAINT
                  ? painted(s.ct(), s.before(), s.bone(), brush)
                  : rubbed(s.before(), s.bone(), brush);
      if (after != null) {
        s.session().set(after);
        showLive(s.ct(), s.session());
      }
    } catch (Problem e) {
      // said on release, when the stroke is applied
    }
  }

  /** The voxels of the stroke's points that lie on the CT, in order. */
  private static List<int[]> strokeVoxels(CtSource ct, List<Seed> seeds) {
    List<int[]> voxels = new ArrayList<>();
    for (Seed seed : seeds) {
      if (seed.frameOfReferenceUID() == null
          || ct.frameOfReferenceUID() == null
          || Objects.equals(seed.frameOfReferenceUID(), ct.frameOfReferenceUID())) {
        ct.voxelOf(seed.lps()).ifPresent(voxels::add);
      }
    }
    return voxels;
  }

  /** Ends the showing of a stroke and puts the bones back as they were before it. */
  private static LiveStroke endLiveStroke() {
    STROKE_TIMER.stop();
    strokeSoFar = null;
    LiveStroke s = liveStroke;
    liveStroke = null;
    if (s != null) {
      s.session().set(s.before());
    }
    return s;
  }

  /** Shows the bones as they were when the stroke that was shown came to nothing. */
  private static void putBack(LiveStroke shown) {
    if (shown != null && shown.session().bones() == shown.before()) {
      showLive(shown.ct(), shown.session());
    }
  }

  /** The bones after Add here on {@code bone} within the brush; null when it adds nothing. */
  private static List<Bone> painted(CtSource ct, List<Bone> bones, Bone bone, BoxMask brush) {
    BoxMask dense = underBrush(ct, brush);
    if (dense == null) {
      return null;
    }
    BoxMask grown = MaskEdit.plus(bone.mask(), dense);
    return grown == bone.mask() ? null : withPainted(bones, bone, grown, dense);
  }

  /** What Add here takes of the brush: all of it, or only what looks like bone, as chosen. */
  private static BoxMask underBrush(CtSource ct, BoxMask brush) {
    if (brush == null || !DENSE.isSelected()) {
      return brush;
    }
    GrowOptions o = options(ct);
    return MaskEdit.dense(brush, ct.huSource(), o.lowerHu(), o.upperHu());
  }

  /**
   * The bones after Take out here on {@code bone} within the brush; null when it changes nothing.
   */
  private static List<Bone> rubbed(List<Bone> bones, Bone bone, BoxMask brush) {
    BoxMask left = bone.mask().minus(List.of(brush));
    if (left == null || left == bone.mask()) {
      return null;
    }
    return bones.stream().map(b -> b.id() == bone.id() ? b.withMask(left) : b).toList();
  }

  /**
   * The list with {@code bone} grown to {@code grown} and {@code dense} out of every other bone.
   */
  private static List<Bone> withPainted(List<Bone> bones, Bone bone, BoxMask grown, BoxMask dense) {
    List<Bone> after = new ArrayList<>();
    for (Bone b : bones) {
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
    return after;
  }

  /** A drag on a slice with the brush on: one Add here or Take out here along the whole stroke. */
  private static void stroked(List<Seed> seeds) {
    LiveStroke shown = endLiveStroke();
    try {
      strokedNow(seeds);
    } finally {
      putBack(shown);
    }
  }

  private static void strokedNow(List<Seed> seeds) {
    if (!isBrushOnSlices() || seeds.isEmpty()) {
      return;
    }
    if (worker != null) {
      status(Messages.getString("BoneGrowTool.busy"));
      return;
    }
    followSlice(seeds.getFirst());
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
      LOGGER.debug("Bones stroke {} of {} points in {}", tool, voxels.size(), seeds.get(0).plane());
      if (tool == Tool.CUT) {
        cutAlong(ct, session, voxels, seeds.getFirst());
        return;
      }
      BoxMask brush = brushOf(ct, voxels, seeds.getFirst());
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
    Optional<Found> found = session.boneNearClick(voxel);
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
    Set<Integer> before = Set.copyOf(selected);
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
    if (pick != Pick.ONE && !before.equals(selected)) {
      // A selection built click by click is a step each: Undo takes back the last click only.
      Set<Integer> after = Set.copyOf(selected);
      selectedView()
          .ifPresent(
              v ->
                  v.pushEdit(
                      new EditHistory.Edit() {
                        @Override
                        public boolean undo() {
                          showSelection(session, before);
                          return true;
                        }

                        @Override
                        public boolean redo() {
                          showSelection(session, after);
                          return true;
                        }
                      }));
    }
    selectionChanged(session);
  }

  /** Makes {@code ids} the selection, as an undo or redo of a selection click does. */
  private static void showSelection(GrowSession session, Set<Integer> ids) {
    session.selected().clear();
    session.bones().stream().map(Bone::id).filter(ids::contains).forEach(session.selected()::add);
    selectionChanged(session);
  }

  private static void selectionChanged(GrowSession session) {
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

  /** The brush as voxel radii per axis: its radius in mm over the voxel spacing. */
  private static double[] brushRadius(CtSource ct) {
    double mm = brushMm();
    double[] voxel = spacing(ct);
    return new double[] {mm / voxel[0], mm / voxel[1], mm / voxel[2]};
  }

  /** The voxel size along the grid's x, y and z, in mm. */
  private static double[] spacing(CtSource ct) {
    return new double[] {
      ct.grid().columnSpacing(), ct.grid().rowSpacing(), Math.abs(ct.grid().sliceSpacing())
    };
  }

  /**
   * The normal of the slice a seed was taken on along the grid's axes, or {@code null} for a seed
   * of the 3D view.
   */
  private static double[] gridNormal(CtSource ct, Seed seed) {
    Vector3d n = seed.normal();
    if (n == null) {
      return null;
    }
    double[] row = ct.grid().rowDirection();
    double[] column = ct.grid().columnDirection();
    double[] slice = ct.grid().normal();
    // The slices may run against their normal.
    double way = ct.grid().sliceSpacing() < 0 ? -1 : 1;
    double[] g = {
      n.x * row[0] + n.y * row[1] + n.z * row[2],
      n.x * column[0] + n.y * column[1] + n.z * column[2],
      way * (n.x * slice[0] + n.y * slice[1] + n.z * slice[2])
    };
    double length = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
    if (!(length > 0)) {
      return null;
    }
    return new double[] {g[0] / length, g[1] / length, g[2] / length};
  }

  /** How thick one layer of voxels is across a slice with this normal, in mm. */
  private static double layerMm(double[] normal, double[] voxel) {
    return Math.abs(normal[0]) * voxel[0]
        + Math.abs(normal[1]) * voxel[1]
        + Math.abs(normal[2]) * voxel[2];
  }

  /**
   * The voxels the brush works on along a click or a drag: a ball around each point, or, with This
   * slice only, a disc one layer of voxels thick in the slice it was drawn on.
   */
  private static BoxMask brushOf(CtSource ct, List<int[]> voxels, Seed seed) {
    double[] normal = THIN.isSelected() ? gridNormal(ct, seed) : null;
    if (normal == null) {
      return MaskEdit.stroke(voxels, brushRadius(ct), gridBox(ct));
    }
    double depth = Math.min(1, 0.6 * layerMm(normal, spacing(ct)) / brushMm());
    return MaskEdit.stroke(voxels, brushRadius(ct), gridBox(ct), normal, depth);
  }

  /**
   * The line of Cut line as it is drawn: a thin mark on its slice, shown rubbed out of the bone.
   */
  private static BoxMask cutLine(CtSource ct, List<int[]> voxels, Seed seed) {
    double[] normal = gridNormal(ct, seed);
    double[] voxel = spacing(ct);
    double mm = 0.75 * Math.max(voxel[0], Math.max(voxel[1], voxel[2]));
    double[] radius = {mm / voxel[0], mm / voxel[1], mm / voxel[2]};
    if (normal == null) {
      return MaskEdit.stroke(voxels, radius, gridBox(ct));
    }
    double depth = Math.min(1, 0.6 * layerMm(normal, voxel) / mm);
    return MaskEdit.stroke(voxels, radius, gridBox(ct), normal, depth);
  }

  /**
   * The bone a drawn line cuts: the one selected when the line meets it, else the first it meets.
   */
  private static Optional<Bone> cutTarget(GrowSession session, List<int[]> voxels) {
    List<Bone> selected = session.selectedBones();
    if (selected.size() == 1) {
      BoxMask mask = selected.getFirst().mask();
      if (voxels.stream().anyMatch(v -> mask.contains(v[0], v[1], v[2]))) {
        return Optional.of(selected.getFirst());
      }
    }
    for (int[] v : voxels) {
      for (Bone b : session.bones()) {
        if (!b.hidden() && b.mask().contains(v[0], v[1], v[2])) {
          return Optional.of(b);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Cut line: the bone is cut in two along the line drawn on the slice, carried straight through
   * the bone; the part that comes away becomes a bone of its own.
   */
  private static void cutAlong(CtSource ct, GrowSession session, List<int[]> voxels, Seed seed) {
    double[] normal = gridNormal(ct, seed);
    Optional<Bone> target = cutTarget(session, voxels);
    if (normal == null || target.isEmpty()) {
      warn(Messages.getString("BoneGrowTool.cutNoBone"));
      return;
    }
    Bone bone = target.get();
    MaskEdit.Cut cut;
    try {
      long crumb = Math.round(MIN_PIECE_MM3 / ct.grid().voxelVolumeMm3());
      cut = MaskEdit.cut(bone.mask(), voxels, spacing(ct), normal, crumb);
    } catch (OutOfMemoryError e) {
      warn(Messages.getString("BoneGrowTool.memory"));
      return;
    }
    if (cut == null) {
      warn(String.format(Messages.getString("BoneGrowTool.cutNot"), bone.label()));
      return;
    }
    int id = session.newId();
    String name = String.format(Messages.getString("BoneGrowTool.pieceName"), bone.label(), id);
    Bone kept = bone.withMask(cut.kept());
    kept =
        new Bone(
            kept.id(),
            kept.label(),
            kept.color(),
            kept.mask(),
            MaskEdit.anchorIn(kept.mask(), kept.anchor()),
            kept.description(),
            kept.hidden());
    Bone piece =
        new Bone(
            id,
            name,
            session.colorOf(id, cut.piece()),
            cut.piece(),
            MaskEdit.anchorIn(cut.piece(), voxels.getLast()),
            "Cut from " + bone.label() + " along a drawn line; not reviewed"); // NON-NLS
    if (!apply(ct, session, session.replaced(bone.id(), kept, piece))) {
      return; // refused and said why: not followed by a message of success
    }
    session.selected().clear();
    session.selected().add(id);
    styleViews(session);
    refreshLists();
    double mm3 = ct.grid().voxelVolumeMm3();
    status(
        String.format(
            Messages.getString("BoneGrowTool.cutDone"),
            name,
            cut.piece().count() * mm3 / 1000.0,
            bone.label(),
            cut.kept().count() * mm3 / 1000.0));
  }

  private static VoxelBox gridBox(CtSource ct) {
    return VoxelBox.of(ct.grid().columns(), ct.grid().rows(), ct.grid().sliceCount());
  }

  /**
   * The bone a brush click works on: the one selected, else the one under the click, else the
   * nearest one the brush reaches.
   */
  private static Optional<Bone> brushTarget(CtSource ct, GrowSession session, int[] voxel) {
    List<Bone> selected = session.selectedBones();
    if (selected.size() == 1) {
      return Optional.of(selected.getFirst());
    }
    if (selected.isEmpty()) {
      double[] mm = spacing(ct);
      return session
          .boneNearClick(voxel)
          .or(() -> session.boneNear(voxel, brushMm(), mm[0], mm[1], mm[2]))
          .map(Found::bone);
    }
    return Optional.empty();
  }

  /**
   * Add here: what looks like bone within the brush goes into the bone, out of any other bone it
   * was in.
   */
  private static void paint(CtSource ct, GrowSession session, int[] voxel, BoxMask brush) {
    Optional<Bone> target = brushTarget(ct, session, voxel);
    if (target.isEmpty()) {
      status(Messages.getString("BoneGrowTool.brushWhich"));
      return;
    }
    Bone bone = target.get();
    BoxMask dense = underBrush(ct, brush);
    if (dense == null) {
      warn(String.format(Messages.getString("BoneGrowTool.paintNothing"), options(ct).lowerHu()));
      return;
    }
    BoxMask grown = MaskEdit.plus(bone.mask(), dense);
    if (grown == bone.mask()) {
      status(String.format(Messages.getString("BoneGrowTool.paintAlready"), bone.label()));
      return;
    }
    List<Bone> after = withPainted(session.bones(), bone, grown, dense);
    session.selected().clear();
    session.selected().add(bone.id());
    if (!apply(ct, session, after)) {
      return; // refused and said why: not followed by a message of success
    }
    double mm3 = (grown.count() - bone.mask().count()) * ct.grid().voxelVolumeMm3();
    status(String.format(Messages.getString("BoneGrowTool.painted"), mm3, bone.label()));
  }

  /** Take out here: the bone's voxels within the brush leave it. */
  private static void rub(CtSource ct, GrowSession session, int[] voxel, BoxMask brush) {
    Optional<Bone> target = brushTarget(ct, session, voxel);
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
    if (!apply(ct, session, session.replaced(bone.id(), bone.withMask(left), null))) {
      return; // refused and said why: not followed by a message of success
    }
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
    if (!apply(ct.get(), session, after)) {
      return; // refused and said why: not followed by a message of success
    }
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
    Optional<Found> found = session.boneNearClick(voxel);
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
            ct.huSource(),
            session.bones()));
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
    start(
        new BoneGrowWorker(
            ct, new Request(voxel, crop, options(ct)), session.masksExcept(-1), session.bones()));
  }

  private static void startErase(GrowSession session, int[] voxel) {
    Optional<Found> found = session.boneNearClick(voxel);
    if (found.isEmpty()) {
      warn(
          Messages.getString(
              session.bones().isEmpty() ? "BoneGrowTool.noBoneYet" : "BoneGrowTool.noBoneThere"));
      return;
    }
    start(
        new SplitWorker(
            SplitWorker.Kind.ERASE, found.get().bone(), found.get().voxel(), session.voxelSize()));
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

  // The colour the worker found for a new bone; worked out here only if the bones changed since.
  private static Color colour(
      int id, BoxMask mask, List<Color> taken, List<Bone> looked, GrowSession session) {
    Color c = BoneColors.pick(id, taken, looked, session.bones());
    return c != null ? c : session.colorOf(id, mask);
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
            colour(id, mask, done.takenColours(), done.others(), session),
            mask,
            result.seed(),
            BoneGrowWorker.description(done.request().options(), result));
    if (!apply(ct, session, session.with(bone))) {
      return; // refused and said why: not followed by a message of success
    }
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
      if (!apply(ct.get(), session, session.replaced(bone.id(), kept, null))) {
        return; // refused and said why: not followed by a message of success
      }
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
              colour(fragmentId, split.cut(), done.takenColours(), done.others(), session),
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
    if (!apply(ct.get(), session, after)) {
      return; // refused and said why: not followed by a message of success
    }
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
    if (!apply(ct, session, hidden(session, b -> ids.contains(b.id()) || b.hidden()))) {
      return; // refused and said why: not followed by a message of success
    }
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
    if (!apply(ct, session, after)) {
      return; // refused and said why: not followed by a message of success
    }
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
    if (!apply(ct, session, session.bones().stream().filter(b -> !ids.contains(b.id())).toList())) {
      return; // refused and said why: not followed by a message of success
    }
    status(
        String.format(
            Messages.getString("BoneGrowTool.deleted"),
            selected.size() == 1 ? selected.getFirst().label() : String.valueOf(selected.size())));
  }

  // ---- The list and its undo history ----

  /**
   * Makes {@code after} the bones of the session as one undoable step of the 3D view, then shows
   * and, when their segments changed, writes them.
   *
   * @return false when the step was refused, with the reason shown: the caller must not then report
   *     success (review of 11 Nov 2026: a search that finished while another CT's 3D view was
   *     selected was thrown away under "Found N bones")
   */
  private static boolean apply(CtSource ct, GrowSession session, List<Bone> after) {
    Optional<View3d> view = selectedView();
    if (view.isEmpty() || view.get().getVolTexture().getVolume() != ct.volume()) {
      // Without the 3D view of this CT the step could not be undone.
      warn(Messages.getString("BoneGrowTool.noCt"));
      return false;
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
    return true;
  }

  private static void bonesChanged(CtSource ct, GrowSession session) {
    refreshLists();
    styleViews(session); // shows the live bones when there are some
    if (!LiveBones.isLive(session)) {
      showLive(ct, session); // the first time: makes them
    }
    if (!session.isWritten()) {
      scheduleWrite(ct, session);
    }
    showStateEverywhere();
  }

  /**
   * Shows the bones at once on the MPR and 3D views, from memory: only the voxels of the bones that
   * changed are written, and the views redraw only the slices they lie in.
   */
  private static void showLive(CtSource ct, GrowSession session) {
    if (ct.volume() == null) {
      return;
    }
    LiveBones.sync(ct, session, SELECTED_COLOR)
        .ifPresent(change -> SegExplorer.liveChanged(ct.series(), change.z0(), change.z1()));
  }

  /** How long after the last change the bones are written as a DICOM SEG file, in ms. */
  private static final int WRITE_DELAY_MS = 2500;

  /** Sessions whose SEG file is to be written once the edits pause, with their CT. */
  private static final Map<GrowSession, CtSource> WRITE_LATER = new LinkedHashMap<>();

  private static final javax.swing.Timer WRITE_TIMER =
      new javax.swing.Timer(WRITE_DELAY_MS, _ -> writeLater());

  static {
    WRITE_TIMER.setRepeats(false);
  }

  /**
   * Writes the SEG file (for saving, the Segmentation panel and the STL export) once the edits
   * pause; the views show the live bones meanwhile. Without live bones it is written at once, as
   * the views show the bones from it.
   */
  private static void scheduleWrite(CtSource ct, GrowSession session) {
    if (!LiveBones.isLive(session)) {
      write(ct, session);
      return;
    }
    WRITE_LATER.put(session, ct);
    WRITE_TIMER.restart();
  }

  private static void writeLater() {
    Map<GrowSession, CtSource> due = new LinkedHashMap<>(WRITE_LATER);
    WRITE_LATER.clear();
    due.forEach(
        (session, ct) -> {
          if (!session.isWritten()) {
            write(ct, session);
          }
        });
  }

  /**
   * Gives the 3D views the bones as they are shown: hidden ones taken out, selected ones in the
   * selection colour. Only the colour table of the views changes.
   */
  private static void styleViews(GrowSession session) {
    if (LiveBones.isLive(session)) {
      currentSource().filter(ct -> session(ct) == session).ifPresent(ct -> showLive(ct, session));
    }
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
      if (!SESSIONS.holds(session)) {
        // The CT was closed during the write: the file is kept, nothing is left to show it on.
        writeNext();
        return;
      }
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
    writeNext();
    refreshLists();
  }

  private static void writeNext() {
    if (!TO_WRITE.isEmpty()) {
      Map.Entry<GrowSession, CtSource> next = TO_WRITE.entrySet().iterator().next();
      TO_WRITE.remove(next.getKey());
      write(next.getValue(), next.getKey());
    }
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
      sliceView = null; // a 3D view was selected: that one is in use again
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
