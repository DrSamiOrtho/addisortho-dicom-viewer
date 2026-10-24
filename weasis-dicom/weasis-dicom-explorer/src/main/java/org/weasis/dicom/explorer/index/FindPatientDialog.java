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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.Messages;

/**
 * Finds a patient among the DICOM studies on this computer: in the saved folders (Telegram
 * downloads among them), or in another folder searched once. Double-click or Open loads the study
 * in the viewer, as an import of its files does.
 */
public final class FindPatientDialog extends JDialog {

  private static FindPatientDialog open;

  private final DicomModel model;
  private final PatientIndexService service = PatientIndexService.getInstance();
  private final JTextField query = new JTextField(28);
  private final StudyTableModel tableModel = new StudyTableModel();
  private final JTable table = new JTable(tableModel);
  private final JLabel status = new JLabel(" ");
  private final JButton openButton = new JButton(Messages.getString("index.open"));
  private final JButton keepButton = new JButton(Messages.getString("index.keep"));
  private final JButton cancelScan = new JButton(Messages.getString("index.cancelScan"));
  private final Timer debounce = new Timer(200, _ -> refresh());
  private final Runnable listener = this::refresh;
  private List<StudyEntry> temporary = List.of();
  private Path temporaryFolder;

  /** Shows the window, one for the whole viewer. */
  public static void show(Component parent, DicomModel model) {
    if (open == null || !open.isDisplayable()) {
      open = new FindPatientDialog(SwingUtilities.getWindowAncestor(parent), model);
    }
    open.setVisible(true);
    open.toFront();
    open.query.requestFocusInWindow();
  }

  private FindPatientDialog(Window owner, DicomModel model) {
    super(owner, Messages.getString("index.title"), ModalityType.MODELESS);
    this.model = model;
    setDefaultCloseOperation(DISPOSE_ON_CLOSE);

    JPanel top = new JPanel(new BorderLayout(6, 4));
    top.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
    top.add(new JLabel(Messages.getString("index.query")), BorderLayout.WEST);
    query.setToolTipText(Messages.getString("index.query.tip"));
    top.add(query, BorderLayout.CENTER);
    JLabel hint = new JLabel(Messages.getString("index.hint"));
    top.add(hint, BorderLayout.SOUTH);
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

    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setAutoCreateRowSorter(true);
    table.getSelectionModel().addListSelectionListener(_ -> updateButtons());
    table.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              openSelected();
            }
          }
        });
    int[] widths = {170, 90, 80, 30, 80, 170, 60, 50, 220};
    for (int i = 0; i < widths.length; i++) {
      table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    }
    JScrollPane scroll = new JScrollPane(table);
    scroll.setPreferredSize(new Dimension(960, 380));

    JButton once = new JButton(Messages.getString("index.once"));
    once.setToolTipText(Messages.getString("index.once.tip"));
    once.addActionListener(_ -> searchOnce());
    keepButton.setToolTipText(Messages.getString("index.keep.tip"));
    keepButton.addActionListener(_ -> keepTemporaryFolder());
    JButton folders = new JButton(Messages.getString("index.folders"));
    folders.addActionListener(_ -> editFolders());
    JButton rescan = new JButton(Messages.getString("index.rescan"));
    rescan.setToolTipText(Messages.getString("index.rescan.tip"));
    rescan.addActionListener(_ -> service.rescanAll());
    cancelScan.addActionListener(_ -> service.cancel());
    JButton clear = new JButton(Messages.getString("index.clear"));
    clear.setToolTipText(Messages.getString("index.clear.tip"));
    clear.addActionListener(_ -> clearIndex());
    openButton.addActionListener(_ -> openSelected());

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEADING));
    actions.add(openButton);
    actions.add(once);
    actions.add(keepButton);
    actions.add(Box.createHorizontalStrut(12));
    actions.add(folders);
    actions.add(rescan);
    actions.add(cancelScan);
    actions.add(clear);
    JPanel bottom = new JPanel();
    bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
    bottom.setBorder(BorderFactory.createEmptyBorder(0, 6, 8, 6));
    bottom.add(actions);
    JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEADING));
    statusRow.add(status);
    bottom.add(statusRow);

    JPanel content = new JPanel(new BorderLayout());
    content.add(top, BorderLayout.NORTH);
    content.add(scroll, BorderLayout.CENTER);
    content.add(bottom, BorderLayout.SOUTH);
    setContentPane(content);
    pack();
    setLocationRelativeTo(owner);

    service.addListener(listener);
    service.start();
    if (service.folders().isEmpty()) {
      SwingUtilities.invokeLater(this::firstUse);
    }
    refresh();
  }

  @Override
  public void dispose() {
    service.removeListener(listener);
    debounce.stop();
    super.dispose();
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
            this, panel, getTitle(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
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
    List<StudyEntry> all = new ArrayList<>(temporary);
    all.addAll(service.studies());
    tableModel.set(PatientMatcher.search(all, query.getText()));
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
    cancelScan.setEnabled(s.running());
    updateButtons();
  }

  private void updateButtons() {
    openButton.setEnabled(table.getSelectedRow() >= 0);
    keepButton.setEnabled(temporaryFolder != null && !temporary.isEmpty());
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
    if (s != null) {
      PatientIndexService.open(s, model);
    }
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
      JOptionPane.showMessageDialog(this, refusal, getTitle(), JOptionPane.WARNING_MESSAGE);
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
            this, Messages.getString("index.clear.confirm"), getTitle(), JOptionPane.YES_NO_OPTION)
        == JOptionPane.YES_OPTION) {
      service.clear();
    }
  }

  /** The rows: one study each, newest first. */
  private static final class StudyTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
      "index.col.patient",
      "index.col.id",
      "index.col.birth",
      "index.col.sex",
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
      return column == 7 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
      StudyEntry s = rows.get(row);
      return switch (column) {
        case 0 -> s.displayName();
        case 1 -> s.patientId();
        case 2 -> PatientMatcher.displayDate(s.birthDate());
        case 3 -> s.sex();
        case 4 -> PatientMatcher.displayDate(s.studyDate());
        case 5 -> s.studyDescription();
        case 6 -> s.modalities();
        case 7 -> s.imageCount();
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
