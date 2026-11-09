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
package org.weasis.dicom.explorer.index;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import net.miginfocom.swing.MigLayout;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.ui.pref.PreferenceDialog;
import org.weasis.core.ui.util.AoDesign;
import org.weasis.core.ui.util.AoFonts;
import org.weasis.core.ui.util.AppShell;
import org.weasis.core.ui.util.ColorLayerUI;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.Messages;
import org.weasis.dicom.explorer.exp.DicomExportFactory;
import org.weasis.dicom.explorer.imp.ImportToolBar;

/**
 * The home of the application: finds a patient among the studies on this computer, in the managed
 * library and the saved folders. Open study loads it in the viewer, as an import of its files does.
 * A light work surface; everything around it stays as it was.
 */
public final class StudiesHome extends JPanel {
  private static final int GAP = AoDesign.GAP;
  private static final int GAP_LARGE = AoDesign.GAP_LARGE;

  /** Below this width of the screen the details become a drawer instead of a column. */
  static final int DETAILS_INLINE_WIDTH = 980;

  private static final int DETAILS_WIDTH = 320;
  private static final int ROW_HEIGHT = 48;
  private static final int C_PATIENT = 0;
  private static final int C_ID = 1;
  private static final int C_DATE = 2;
  private static final int C_STUDY = 3;
  private static final int C_MODALITY = 4;
  private static final int C_IMAGES = 5;
  private static final int C_WHERE = 6;
  private static final String CARD_TABLE = "table"; // NON-NLS
  private static final String CARD_EMPTY = "empty"; // NON-NLS

  private final DicomModel model;
  private final PatientIndexService service = PatientIndexService.getInstance();
  private final JTextField query = new JTextField();
  private final StudyTableModel tableModel = new StudyTableModel();
  private final JTable table = new JTable(tableModel);
  private final List<TableColumn> columns = new ArrayList<>();
  private final JLabel count = new JLabel(" ");
  private final JLabel status = new JLabel(" ");
  private final JButton openButton = new JButton(Messages.getString("index.open.study"));
  private final JButton headerOpen = new JButton(Messages.getString("index.open.study"));
  private final JButton detailsButton = new JButton(Messages.getString("index.details"));
  private final JButton exportButton = new JButton(Messages.getString("index.export"));
  private final JButton sendButton = new JButton(Messages.getString("index.send"));
  private final JMenuItem keepItem = new JMenuItem(Messages.getString("index.keep"));
  private final JMenuItem cancelScan = new JMenuItem(Messages.getString("index.cancelScan"));
  private final JLabel receiveStatus = new JLabel();
  private final CardLayout tableCards = new CardLayout();
  private final JPanel tableArea = new JPanel(tableCards);
  private final JLabel emptyTitle = new JLabel(" ", SwingConstants.CENTER);
  private final JLabel emptyText = new JLabel(" ", SwingConstants.CENTER);
  private final JPanel details = new JPanel();
  private final JTextArea detailName = valueArea();
  private final JPanel detailRows = new JPanel();
  private boolean drawerOpen;
  private final Timer debounce = new Timer(200, _ -> refresh());
  private List<StudyEntry> temporary = List.of();
  private Path temporaryFolder;

  private static volatile String receivePage;
  private static volatile Supplier<String> receiveState = () -> "";

  /**
   * Called by the receive module, which this one cannot see.
   *
   * @param pageTitle its page in the preferences
   * @param state its current state, in words
   */
  public static void registerReceive(String pageTitle, Supplier<String> state) {
    receivePage = pageTitle;
    receiveState = state;
  }

  public StudiesHome(DicomModel model) {
    super(new BorderLayout());
    this.model = model;
    setBackground(AoDesign.WORK_PAGE);
    setFocusable(false);

    add(buildHeader(), BorderLayout.NORTH);
    add(buildBody(), BorderLayout.CENTER);
    add(buildFooter(), BorderLayout.SOUTH);
    buildTable();
    AoDesign.lightSurface(this);
    // The table hands its header to the scroll pane only once it is on screen.
    AoDesign.lightSurface(table.getTableHeader());
    styleAfterLightSurface();

    addComponentListener(
        new ComponentAdapter() {
          @Override
          public void componentResized(ComponentEvent e) {
            fitToWidth();
          }
        });
    service.addListener(this::refresh);
    CompletableFuture.runAsync(service::start).thenRun(() -> GuiExecutor.execute(this::refresh));
    refresh();
  }

  @Override
  public boolean requestFocusInWindow() {
    return query.requestFocusInWindow();
  }

  private JPanel buildHeader() {
    JLabel title = new JLabel(Messages.getString("index.title"));
    title.setFont(AoFonts.semibold(20));
    title.putClientProperty(AoDesign.KEEP_FONT, Boolean.TRUE);
    count.setForeground(AoDesign.WORK_TEXT_SECONDARY);
    JPanel names = new JPanel(new BorderLayout(GuiUtils.getScaleLength(GAP_LARGE), 0));
    names.setOpaque(false);
    names.add(title, BorderLayout.WEST);
    names.add(count, BorderLayout.CENTER);

    JButton cd = new JButton(Messages.getString("index.open.cd"), discIcon());
    cd.setMnemonic(KeyEvent.VK_C);
    cd.addActionListener(_ -> openCd());
    JButton importButton = new JButton(Messages.getString("index.import"));
    importButton.setMnemonic(KeyEvent.VK_I);
    importButton.addActionListener(_ -> ImportToolBar.showAction(this, model, null, false));
    JButton receiveButton = new JButton(Messages.getString("index.receive"));
    receiveButton.setMnemonic(KeyEvent.VK_R);
    receiveButton.addActionListener(_ -> openReceive());
    receiveButton.setVisible(receivePage != null); // the Lite editions may not have the receiver
    JPopupMenu moreMenu = buildMoreMenu();
    JButton more = new JButton(Messages.getString("index.more"));
    more.addActionListener(_ -> moreMenu.show(more, 0, more.getHeight()));
    headerOpen.setMnemonic(KeyEvent.VK_O);
    headerOpen.addActionListener(_ -> openSelected());
    AoDesign.primary(headerOpen);
    detailsButton.addActionListener(_ -> setDrawerOpen(!drawerOpen));

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, GAP, 0));
    actions.setOpaque(false);
    actions.add(headerOpen);
    actions.add(detailsButton);
    actions.add(cd);
    actions.add(importButton);
    actions.add(receiveButton);
    actions.add(more);

    JPanel row = new JPanel(new BorderLayout(GAP, 0));
    row.setOpaque(false);
    row.add(names, BorderLayout.WEST);
    row.add(actions, BorderLayout.EAST);

    query.setToolTipText(Messages.getString("index.query.tip"));
    query.putClientProperty(
        FlatClientProperties.PLACEHOLDER_TEXT, Messages.getString("index.placeholder"));
    query.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
    query.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, searchIcon());
    debounce.setRepeats(false);
    query
        .getDocument()
        .addDocumentListener(
            new DocumentListener() {
              @Override
              public void insertUpdate(DocumentEvent e) {
                debounce.restart();
              }

              @Override
              public void removeUpdate(DocumentEvent e) {
                debounce.restart();
              }

              @Override
              public void changedUpdate(DocumentEvent e) {
                debounce.restart();
              }
            });
    query.addActionListener(_ -> openSelected());
    bindMoveSelection(KeyEvent.VK_DOWN, 1);
    bindMoveSelection(KeyEvent.VK_UP, -1);

    JPanel header = new JPanel(new BorderLayout(0, GuiUtils.getScaleLength(GAP)));
    header.setOpaque(false);
    int side = GuiUtils.getScaleLength(24);
    int top = GuiUtils.getScaleLength(12);
    header.setBorder(
        BorderFactory.createEmptyBorder(top, side, GuiUtils.getScaleLength(GAP), side));
    header.add(row, BorderLayout.NORTH);
    header.add(query, BorderLayout.SOUTH);
    return header;
  }

  private JPopupMenu buildMoreMenu() {
    JMenuItem once = new JMenuItem(Messages.getString("index.once"));
    once.setToolTipText(Messages.getString("index.once.tip"));
    once.addActionListener(_ -> searchOnce());
    keepItem.setToolTipText(Messages.getString("index.keep.tip"));
    keepItem.addActionListener(_ -> keepTemporaryFolder());
    JMenuItem folders = new JMenuItem(Messages.getString("index.folders"));
    folders.addActionListener(_ -> editFolders());
    JMenuItem rescan = new JMenuItem(Messages.getString("index.rescan"));
    rescan.setToolTipText(Messages.getString("index.rescan.tip"));
    rescan.addActionListener(_ -> service.rescanAll());
    cancelScan.addActionListener(_ -> service.cancel());
    JMenuItem clear = new JMenuItem(Messages.getString("index.clear"));
    clear.setToolTipText(Messages.getString("index.clear.tip"));
    clear.addActionListener(_ -> clearIndex());
    JPopupMenu moreMenu = new JPopupMenu();
    moreMenu.add(once);
    moreMenu.add(keepItem);
    moreMenu.addSeparator();
    moreMenu.add(folders);
    moreMenu.add(rescan);
    moreMenu.add(cancelScan);
    moreMenu.addSeparator();
    moreMenu.add(clear);
    AoDesign.lightMenu(moreMenu);
    return moreMenu;
  }

  private JComponent buildBody() {
    JScrollPane scroll = new JScrollPane(table);
    tableArea.setOpaque(false);
    tableArea.add(scroll, CARD_TABLE);
    tableArea.add(buildEmptyState(), CARD_EMPTY);
    JPanel left = new JPanel(new BorderLayout());
    left.setOpaque(false);
    int side = GuiUtils.getScaleLength(24);
    left.setBorder(BorderFactory.createEmptyBorder(0, side, GuiUtils.getScaleLength(GAP), side));
    left.add(tableArea, BorderLayout.CENTER);

    buildDetails();
    JLayeredPane body = new JLayeredPane();
    body.setLayout(new BodyLayout(left));
    body.add(left, JLayeredPane.DEFAULT_LAYER);
    body.add(details, JLayeredPane.PALETTE_LAYER);
    return body;
  }

  /** The table takes what the details leave; on a narrow screen the details lie over it. */
  private final class BodyLayout implements LayoutManager {
    private final JComponent left;

    BodyLayout(JComponent left) {
      this.left = left;
    }

    @Override
    public void layoutContainer(Container parent) {
      int w = parent.getWidth();
      int h = parent.getHeight();
      int detailWidth = GuiUtils.getScaleLength(DETAILS_WIDTH);
      boolean inline = isDetailsInline();
      left.setBounds(0, 0, inline ? w - detailWidth : w, h);
      details.setVisible(inline || drawerOpen);
      details.setBounds(w - detailWidth, 0, detailWidth, h);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
      return left.getPreferredSize();
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
      return new Dimension(0, 0);
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
      // Laid out by reference.
    }

    @Override
    public void removeLayoutComponent(Component comp) {
      // Laid out by reference.
    }
  }

  private boolean isDetailsInline() {
    return getWidth() >= GuiUtils.getScaleLength(DETAILS_INLINE_WIDTH);
  }

  private void setDrawerOpen(boolean open) {
    drawerOpen = open;
    revalidate();
    repaint();
  }

  private void buildDetails() {
    details.setLayout(new BorderLayout());
    details.setBackground(AoDesign.WORK_CARD);
    details.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, AoDesign.WORK_HAIRLINE));
    openButton.setMnemonic(KeyEvent.VK_O);
    openButton.addActionListener(_ -> openSelected());
    AoDesign.primary(openButton);
    exportButton.setMnemonic(KeyEvent.VK_E);
    exportButton.addActionListener(_ -> share(Messages.getString("LocalExport.local_dev")));
    sendButton.setMnemonic(KeyEvent.VK_S);
    sendButton.addActionListener(_ -> share(Messages.getString("index.send.page")));
    sendButton.setVisible(sendIsInstalled()); // the Lite editions have no send page
    detailName.setFont(AoFonts.semibold(16));
    detailName.putClientProperty(AoDesign.KEEP_FONT, Boolean.TRUE);

    detailRows.setOpaque(false);
    detailRows.setLayout(
        new MigLayout("wrap 2, fillx, ins 0, gapy 4lp", "[88lp!][grow,fill]")); // NON-NLS

    TrackingPanel content = new TrackingPanel();
    content.setOpaque(false);
    content.setLayout(
        new MigLayout(
            "wrap 1, fillx, ins 16lp 24lp 16lp 16lp, gapy 8lp", "[grow,fill]")); // NON-NLS
    content.add(new JLabel(Messages.getString("index.selected")));
    content.add(detailName, "wmin 0"); // NON-NLS
    content.add(openButton);
    JPanel share = new JPanel(new GridLayout(1, 0, GuiUtils.getScaleLength(GAP), 0));
    share.setOpaque(false);
    share.add(exportButton);
    if (sendButton.isVisible()) {
      share.add(sendButton);
    }
    content.add(share);
    content.add(detailRows, "gaptop 8lp, wmin 0"); // NON-NLS
    JScrollPane scroll = new JScrollPane(content);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setOpaque(false);
    scroll.getViewport().setOpaque(false);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    details.add(scroll, BorderLayout.CENTER);
    details.registerKeyboardAction(
        _ -> setDrawerOpen(false),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
        WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
  }

  /** A panel in a scroll pane that wraps its text to the width it is given. */
  private static final class TrackingPanel extends JPanel implements Scrollable {
    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
      return GuiUtils.getScaleLength(16);
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return visible.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  private static JTextArea valueArea() {
    JTextArea area = new JTextArea();
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setBorder(BorderFactory.createEmptyBorder());
    return area;
  }

  private JPanel buildEmptyState() {
    emptyTitle.setFont(AoFonts.semibold(16));
    emptyTitle.putClientProperty(AoDesign.KEEP_FONT, Boolean.TRUE);
    emptyText.setForeground(AoDesign.WORK_TEXT_SECONDARY);
    JButton cd = new JButton(Messages.getString("index.open.cd"), discIcon());
    cd.addActionListener(_ -> openCd());
    AoDesign.primary(cd);
    JButton importButton = new JButton(Messages.getString("index.import"));
    importButton.addActionListener(_ -> ImportToolBar.showAction(this, model, null, false));
    JButton folders = new JButton(Messages.getString("index.folders"));
    folders.addActionListener(_ -> firstUse());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, GAP, 0));
    buttons.setOpaque(false);
    buttons.add(cd);
    buttons.add(importButton);
    buttons.add(folders);
    JPanel center = new JPanel(new MigLayout("wrap 1, ins 0, gapy 8lp", "[center]")); // NON-NLS
    center.setOpaque(false);
    center.add(emptyTitle);
    center.add(emptyText);
    center.add(buttons, "gaptop 8lp"); // NON-NLS
    JPanel state = new JPanel(new GridBagLayout());
    state.setBackground(AoDesign.WORK_CARD);
    state.setBorder(BorderFactory.createLineBorder(AoDesign.WORK_HAIRLINE));
    state.add(center);
    return state;
  }

  private JPanel buildFooter() {
    status.setForeground(AoDesign.WORK_TEXT_SECONDARY);
    receiveStatus.setForeground(AoDesign.WORK_TEXT_SECONDARY);
    JPanel footer = new JPanel(new BorderLayout(GAP_LARGE, 0));
    footer.setBackground(AoDesign.WORK_PAGE);
    int side = GuiUtils.getScaleLength(24);
    footer.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AoDesign.WORK_HAIRLINE),
            BorderFactory.createEmptyBorder(
                GuiUtils.getScaleLength(4), side, GuiUtils.getScaleLength(4), side)));
    footer.add(status, BorderLayout.CENTER);
    footer.add(receiveStatus, BorderLayout.EAST);
    return footer;
  }

  private void buildTable() {
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoCreateRowSorter(true);
    table.setRowHeight(GuiUtils.getScaleLength(ROW_HEIGHT));
    table.setShowVerticalLines(false);
    table.setShowHorizontalLines(true);
    table.setIntercellSpacing(new Dimension(0, 1));
    table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
    table.getTableHeader().setReorderingAllowed(false);
    table.getSelectionModel().addListSelectionListener(_ -> updateSelection());
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              openSelected();
            }
          }
        });
    // Tab leaves the table for the next control, and Enter opens the selected study.
    table.setFocusTraversalKeys(
        KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS,
        Set.of(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)));
    table.setFocusTraversalKeys(
        KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS,
        Set.of(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK)));
    table
        .getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openStudy"); // NON-NLS
    table
        .getActionMap()
        .put(
            "openStudy", // NON-NLS
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                openSelected();
              }
            });
    int[] widths = {280, 120, 104, 220, 80, 64, 220};
    StudyRenderer renderer = new StudyRenderer();
    for (int i = 0; i < widths.length; i++) {
      TableColumn column = table.getColumnModel().getColumn(i);
      column.setPreferredWidth(GuiUtils.getScaleLength(widths[i]));
      column.setCellRenderer(renderer);
      if (i == C_ID || i == C_DATE) {
        column.setMinWidth(GuiUtils.getScaleLength(widths[i] - 8)); // never cut short
      }
      columns.add(column);
    }
  }

  private void styleAfterLightSurface() {
    detailsButton.setVisible(false);
    headerOpen.setVisible(false);
  }

  private void bindMoveSelection(int key, int step) {
    query.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), "move" + step); // NON-NLS
    query
        .getActionMap()
        .put(
            "move" + step, // NON-NLS
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                int rows = table.getRowCount();
                if (rows > 0) {
                  int row = Math.clamp(table.getSelectedRow() + step, 0, rows - 1);
                  table.changeSelection(row, 0, false, false);
                }
              }
            });
  }

  /** Which columns fit, the patient, record number and date always. */
  static Set<Integer> columnsFor(int tableWidth) {
    Set<Integer> shown = new TreeSet<>(List.of(C_PATIENT, C_ID, C_DATE));
    if (tableWidth >= 620) {
      shown.add(C_STUDY);
    }
    if (tableWidth >= 700) {
      shown.add(C_MODALITY);
    }
    if (tableWidth >= 780) {
      shown.add(C_IMAGES);
    }
    if (tableWidth >= 900) {
      shown.add(C_WHERE);
    }
    return shown;
  }

  /** Adapts to the width: columns give way by priority, text never shrinks. */
  private void fitToWidth() {
    boolean inline = isDetailsInline();
    headerOpen.setVisible(!inline);
    detailsButton.setVisible(!inline);
    if (inline) {
      drawerOpen = false;
    }
    int side = GuiUtils.getScaleLength(48);
    int tableWidth = getWidth() - side - (inline ? GuiUtils.getScaleLength(DETAILS_WIDTH) : 0);
    Set<Integer> wanted = columnsFor(tableWidth);
    TableColumnModel current = table.getColumnModel();
    if (current.getColumnCount() != wanted.size()) {
      for (TableColumn column : columns) {
        current.removeColumn(column);
      }
      for (TableColumn column : columns) {
        if (wanted.contains(column.getModelIndex())) {
          current.addColumn(column);
        }
      }
    }
    revalidate();
  }

  private void openCd() {
    ImportToolBar.openImportDicomCdAction(this, model, Messages.getString("DicomExplorer.dcmCD"));
  }

  private static Icon discIcon() {
    return tinted("ao/icons/disc.svg", AoDesign.WORK_TEXT); // NON-NLS
  }

  private static Icon searchIcon() {
    return tinted("ao/icons/search.svg", AoDesign.WORK_TEXT_SECONDARY); // NON-NLS
  }

  private static Icon tinted(String path, Color color) {
    FlatSVGIcon icon = new FlatSVGIcon(path, 16, 16, AoDesign.class.getClassLoader());
    icon.setColorFilter(new FlatSVGIcon.ColorFilter(_ -> color));
    return icon;
  }

  private static String title() {
    return Messages.getString("index.title");
  }

  /** The first time: offer the Telegram, Downloads and Desktop folders that exist, ticked. */
  private void firstUse() {
    List<Path> suggested = PatientIndexService.suggestedFolders();
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.add(new JLabel(Messages.getString("index.firstUse")));
    List<JCheckBox> boxes = new ArrayList<>();
    for (Path p : suggested) {
      JCheckBox box = new JCheckBox(p.toString(), true);
      boxes.add(box);
      panel.add(box);
    }
    if (suggested.isEmpty()) {
      panel.add(new JLabel(Messages.getString("index.noSuggestion")));
    }
    if (JOptionPane.showConfirmDialog(
            this, panel, title(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        != JOptionPane.OK_OPTION) {
      return;
    }
    List<Path> chosen = new ArrayList<>();
    for (int i = 0; i < boxes.size(); i++) {
      if (boxes.get(i).isSelected()) {
        chosen.add(suggested.get(i));
      }
    }
    if (chosen.isEmpty()) {
      editFolders();
    } else {
      service.setFolders(chosen);
      service.rescanAll();
    }
  }

  private void refresh() {
    String selectedUid = selected() == null ? null : selected().studyUid();
    List<StudyEntry> all = new ArrayList<>(temporary);
    all.addAll(service.studies());
    List<StudyEntry> found = PatientMatcher.search(all, query.getText());
    tableModel.set(found);
    for (int i = 0; selectedUid != null && i < tableModel.getRowCount(); i++) {
      if (selectedUid.equals(tableModel.get(i).studyUid())) {
        int row = table.convertRowIndexToView(i);
        table.getSelectionModel().setSelectionInterval(row, row);
        break;
      }
    }
    count.setText(MessageFormat.format(Messages.getString("index.count"), all.size()));
    PatientIndexService.Status s = service.status();
    String text;
    if (s.running()) {
      text =
          String.format(
              Messages.getString("index.status.scanning"),
              s.filesSeen(),
              s.dicomFound(),
              s.folder() == null ? "" : s.folder());
    } else {
      text =
          String.format(
              Messages.getString("index.status.done"),
              service.studies().size(),
              service.folders().size());
      if (!service.notIndexed().isEmpty()) {
        text +=
            " "
                + String.format(
                    Messages.getString("index.status.archives"), service.notIndexed().size());
      }
    }
    status.setText(text);
    status.setToolTipText(text);
    receiveStatus.setText(receiveState.get());
    cancelScan.setEnabled(s.running());
    showRowsOrEmptyState(found.isEmpty(), s.running());
    updateSelection();
  }

  private void showRowsOrEmptyState(boolean none, boolean scanning) {
    if (none) {
      boolean searched = !query.getText().isBlank();
      emptyTitle.setText(
          Messages.getString(
              searched
                  ? "index.empty.nomatch"
                  : scanning ? "index.empty.looking" : "index.empty.title"));
      emptyText.setText(
          Messages.getString(searched ? "index.empty.nomatch.text" : "index.empty.text"));
    }
    tableCards.show(tableArea, none ? CARD_EMPTY : CARD_TABLE);
  }

  private void updateSelection() {
    StudyEntry s = selected();
    boolean selected = s != null;
    boolean study = selected && !s.isLocalPicture();
    openButton.setEnabled(selected);
    headerOpen.setEnabled(selected);
    exportButton.setEnabled(study);
    sendButton.setEnabled(study);
    keepItem.setEnabled(temporaryFolder != null && !temporary.isEmpty());
    showDetails(s);
  }

  private void showDetails(StudyEntry s) {
    detailRows.removeAll();
    detailName.setText(s == null ? Messages.getString("index.none.selected") : s.displayName());
    if (s != null) {
      addDetail("index.col.id", s.patientId());
      addDetail("index.col.birth", PatientMatcher.displayDate(s.birthDate()));
      addDetail("index.col.sex", s.sex());
      addDetail("index.col.date", PatientMatcher.displayDate(s.studyDate()));
      addDetail("index.col.description", s.studyDescription());
      addDetail("index.col.modality", s.modalities());
      addDetail("index.col.images", String.valueOf(s.imageCount()));
      addDetail("index.col.where", String.join("\n", s.places()));
    }
    AoDesign.lightSurface(detailRows);
    detailRows.revalidate();
    detailRows.repaint();
  }

  private void addDetail(String key, String value) {
    JLabel label = new JLabel(Messages.getString(key));
    label.setForeground(AoDesign.WORK_TEXT_SECONDARY);
    JTextArea area = valueArea();
    area.setText(value == null || value.isBlank() ? Messages.getString("index.unknown") : value);
    detailRows.add(label, "aligny top"); // NON-NLS
    detailRows.add(area, "wmin 0"); // NON-NLS
  }

  private StudyEntry selected() {
    int row = table.getSelectedRow();
    return row < 0 ? null : tableModel.get(table.convertRowIndexToModel(row));
  }

  private void openSelected() {
    StudyEntry s = selected();
    if (s == null && tableModel.getRowCount() == 1) {
      s = tableModel.get(0);
    }
    if (s == null) {
      return;
    }
    if (!s.isLocalPicture()) {
      if (s.containers().stream().noneMatch(c -> new File(c).canRead())) {
        JOptionPane.showMessageDialog(
            this, Messages.getString("index.unavailable"), title(), JOptionPane.WARNING_MESSAGE);
        return;
      }
      AppShell.getInstance().showViewer();
    }
    PatientIndexService.open(s, model);
  }

  private boolean sendIsInstalled() {
    BundleContext context = AppProperties.getBundleContext(getClass());
    try {
      return context != null
          && !context
              .getServiceReferences(
                  DicomExportFactory.class,
                  "(component.name=org.weasis.dicom.send.SendDicomFactory)")
              .isEmpty();
    } catch (InvalidSyntaxException e) {
      return false;
    }
  }

  /** Brings the study into the viewer's list, without a viewer, then opens the page to share it. */
  private void share(String page) {
    StudyEntry s = selected();
    if (s == null) {
      return;
    }
    PatientIndexService.load(s, model, OpeningViewer.NONE);
    // the loader is single-threaded: this runs once the study is in the list
    DicomModel.LOADING_EXECUTOR.execute(
        () -> SwingUtilities.invokeLater(() -> ImportToolBar.showAction(this, model, page, true)));
  }

  private void openReceive() {
    Window window = SwingUtilities.getWindowAncestor(this);
    PreferenceDialog dialog = new PreferenceDialog(window);
    dialog.showPage(receivePage);
    ColorLayerUI.showCenterScreen(dialog, ColorLayerUI.createTransparentLayerUI(this));
    refresh();
  }

  private Path chooseFolder() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION
        || chooser.getSelectedFile() == null) {
      return null;
    }
    Path folder = chooser.getSelectedFile().toPath();
    String refusal = PatientIndexService.refusal(folder);
    if (refusal != null) {
      JOptionPane.showMessageDialog(this, refusal, title(), JOptionPane.WARNING_MESSAGE);
      return null;
    }
    return folder;
  }

  private void searchOnce() {
    Path folder = chooseFolder();
    if (folder == null) {
      return;
    }
    temporaryFolder = folder;
    service.scanOnce(
        folder,
        found -> {
          temporary = found;
          refresh();
        });
  }

  private void keepTemporaryFolder() {
    if (temporaryFolder == null) {
      return;
    }
    List<Path> folders = new ArrayList<>(service.folders());
    folders.add(temporaryFolder);
    service.setFolders(folders);
    temporary = List.of();
    temporaryFolder = null;
    service.rescanAll();
    refresh();
  }

  private void editFolders() {
    DefaultListModel<Path> list = new DefaultListModel<>();
    service.folders().forEach(list::addElement);
    JList<Path> jlist = new JList<>(list);
    jlist.setVisibleRowCount(8);
    JButton add = new JButton(Messages.getString("index.add"));
    add.addActionListener(
        _ -> {
          Path p = chooseFolder();
          if (p != null && !list.contains(p)) {
            list.addElement(p);
          }
        });
    JButton suggest = new JButton(Messages.getString("index.addSuggested"));
    suggest.setToolTipText(Messages.getString("index.addSuggested.tip"));
    suggest.addActionListener(
        _ ->
            PatientIndexService.suggestedFolders().stream()
                .filter(p -> !list.contains(p))
                .forEach(list::addElement));
    JButton remove = new JButton(Messages.getString("index.remove"));
    remove.addActionListener(_ -> jlist.getSelectedValuesList().forEach(list::removeElement));
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEADING));
    buttons.add(add);
    buttons.add(suggest);
    buttons.add(remove);
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.add(new JLabel(Messages.getString("index.folders.hint")), BorderLayout.NORTH);
    JScrollPane scroll = new JScrollPane(jlist);
    scroll.setPreferredSize(new Dimension(560, 180));
    panel.add(scroll, BorderLayout.CENTER);
    panel.add(buttons, BorderLayout.SOUTH);
    if (JOptionPane.showConfirmDialog(
            this,
            panel,
            Messages.getString("index.folders"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE)
        == JOptionPane.OK_OPTION) {
      List<Path> folders = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        folders.add(list.get(i));
      }
      service.setFolders(folders);
      service.rescanAll();
    }
  }

  private void clearIndex() {
    if (JOptionPane.showConfirmDialog(
            this, Messages.getString("index.clear.confirm"), title(), JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION) {
      service.clear();
    }
  }

  /** Two lines for the patient; the name in full stays in the details. */
  private final class StudyRenderer extends DefaultTableCellRenderer {
    private final JPanel twoLines = new JPanel(new GridLayout(2, 1));
    private final JLabel name = new JLabel();
    private final JLabel sub = new JLabel();

    StudyRenderer() {
      name.setFont(AoFonts.semibold(14));
      sub.setFont(AoFonts.regular(12));
      name.setForeground(AoDesign.WORK_TEXT);
      sub.setForeground(AoDesign.WORK_TEXT_SECONDARY);
      twoLines.add(name);
      twoLines.add(sub);
    }

    @Override
    public Component getTableCellRendererComponent(
        JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
      int modelColumn = t.convertColumnIndexToModel(column);
      JComponent cell;
      boolean needsLayout = false;
      if (modelColumn == C_PATIENT) {
        StudyEntry s = tableModel.get(t.convertRowIndexToModel(row));
        name.setText(s.displayName());
        name.setToolTipText(s.displayName());
        sub.setText(subLine(s));
        twoLines.setBackground(isSelected ? AoDesign.WORK_SELECTION : AoDesign.WORK_CARD);
        cell = twoLines;
        needsLayout = true;
      } else {
        cell =
            (JComponent)
                super.getTableCellRendererComponent(t, value, isSelected, false, row, column);
        setFont(AoFonts.regular(13));
        setBackground(isSelected ? AoDesign.WORK_SELECTION : AoDesign.WORK_CARD);
        setForeground(AoDesign.WORK_TEXT);
        setToolTipText(value == null ? null : value.toString());
      }
      int pad = GuiUtils.getScaleLength(GAP);
      Border inner = BorderFactory.createEmptyBorder(0, pad, 0, pad);
      if (isSelected && column == 0) {
        // Selection is shown by a mark as well as by a colour.
        inner =
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                    0, GuiUtils.getScaleLength(3), 0, 0, AoDesign.GREEN),
                inner);
      }
      if (hasFocus) {
        inner =
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(AoDesign.GREEN, GuiUtils.getScaleLength(1)), inner);
      }
      cell.setBorder(inner);
      if (needsLayout) {
        // A renderer panel is not in a live window, so it would not lay out its own lines.
        cell.setSize(t.getColumnModel().getColumn(column).getWidth(), t.getRowHeight(row));
        cell.doLayout();
      }
      return cell;
    }

    private static String subLine(StudyEntry s) {
      String born = PatientMatcher.displayDate(s.birthDate());
      String text =
          born == null || born.isBlank()
              ? Messages.getString("index.born.unknown")
              : MessageFormat.format(Messages.getString("index.born"), born);
      return s.sex() == null || s.sex().isBlank() ? text : text + ", " + s.sex();
    }
  }

  /** The rows: one study each. */
  private static final class StudyTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
      "index.col.patient",
      "index.col.id",
      "index.col.date",
      "index.col.description",
      "index.col.modality",
      "index.col.images",
      "index.col.where"
    };
    private List<StudyEntry> rows = List.of();

    void set(List<StudyEntry> studies) {
      rows = studies;
      fireTableDataChanged();
    }

    StudyEntry get(int row) {
      return rows.get(row);
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return Messages.getString(COLUMNS[column]);
    }

    @Override
    public Class<?> getColumnClass(int column) {
      return column == C_IMAGES ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
      StudyEntry s = rows.get(row);
      return switch (column) {
        case C_PATIENT -> s.displayName();
        case C_ID -> s.patientId();
        case C_DATE -> PatientMatcher.displayDate(s.studyDate());
        case C_STUDY -> s.studyDescription();
        case C_MODALITY -> s.modalities();
        case C_IMAGES -> s.imageCount();
        default -> where(s);
      };
    }

    private static String where(StudyEntry s) {
      String first = s.places().isEmpty() ? "" : s.places().getFirst();
      String more = s.places().size() > 1 ? " (+" + (s.places().size() - 1) + ")" : "";
      return (s.temporary() ? Messages.getString("index.temporary") + " " : "") + first + more;
    }
  }
}
