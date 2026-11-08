/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AbstractItemDialogPage;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.ui.pref.PreferenceDialog;
import org.weasis.core.util.FileUtil;
import org.weasis.dicom.explorer.library.StoredStudy;
import org.weasis.dicom.explorer.library.StudyStore;

/** Preferences page of the receiver: settings, allowed senders, and the kept studies. */
public class ReceivePrefView extends AbstractItemDialogPage {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReceivePrefView.class);
  private static final int AE_COLUMNS = 16;
  private static final int ADDRESS_COLUMNS = 16;
  private static final int FOLDER_COLUMNS = 32;
  private static final int MAX_CONNECTIONS = 64;
  private static final int MAX_FILE_MB = 1024 * 1024;

  private final JCheckBox enabled = new JCheckBox(Messages.getString("receive.enable"));
  private final JTextField aeTitle = new JTextField(AE_COLUMNS);
  private final JSpinner port = new JSpinner(new SpinnerNumberModel(11112, 1, 65535, 1));
  private final JTextField listenAddress = new JTextField(ADDRESS_COLUMNS);
  private final JTextField folder = new JTextField(FOLDER_COLUMNS);
  private final JSpinner maxAssociations =
      new JSpinner(new SpinnerNumberModel(4, 1, MAX_CONNECTIONS, 1));
  private final JSpinner maxFileMb = new JSpinner(new SpinnerNumberModel(1024, 1, MAX_FILE_MB, 64));
  private final JCheckBox includeName = new JCheckBox(Messages.getString("receive.include.name"));
  private final DefaultTableModel sendersModel =
      new DefaultTableModel(
          new Object[] {
            Messages.getString("receive.sender.ae"), Messages.getString("receive.sender.ip")
          },
          0);
  private final JTable sendersTable = new JTable(sendersModel);
  private final JLabel status = new JLabel();
  private final JLabel total = new JLabel();
  private final DefaultTableModel studiesModel =
      new DefaultTableModel(
          new Object[] {
            Messages.getString("receive.studies.folder"),
            Messages.getString("receive.studies.images"),
            Messages.getString("receive.studies.size")
          },
          0) {
        @Override
        public boolean isCellEditable(int row, int column) {
          return false;
        }
      };
  private final JTable studiesTable = new JTable(studiesModel);
  private List<StoredStudy> studies = List.of();

  public ReceivePrefView() {
    super(Messages.getString("receive.title"), 608);
    port.setEditor(new JSpinner.NumberEditor(port, "#"));
    maxFileMb.setEditor(new JSpinner.NumberEditor(maxFileMb, "#"));
    add(buildSettingsPanel());
    add(GuiUtils.boxVerticalStrut(BLOCK_SEPARATOR));
    add(buildSendersPanel());
    add(GuiUtils.boxVerticalStrut(BLOCK_SEPARATOR));
    add(buildStudiesPanel());
    add(GuiUtils.boxYLastElement(LAST_FILLER_HEIGHT));

    getProperties().setProperty(PreferenceDialog.KEY_SHOW_APPLY, Boolean.TRUE.toString());
    getProperties().setProperty(PreferenceDialog.KEY_SHOW_RESTORE, Boolean.TRUE.toString());

    show(ReceiveService.get().map(ReceiveService::settings).orElseGet(ReceiveSettings::defaults));
    refreshStatus();
    refreshStudies();
  }

  private JPanel buildSettingsPanel() {
    JPanel panel = GuiUtils.getVerticalBoxLayoutPanel();
    panel.setBorder(GuiUtils.getTitledBorder(Messages.getString("receive.title")));
    panel.add(GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, ITEM_SEPARATOR, enabled));
    panel.add(row("receive.ae.title", aeTitle));
    panel.add(row("receive.port", port));
    panel.add(row("receive.listen.address", listenAddress));
    JButton browse = new JButton(Messages.getString("receive.browse"));
    browse.addActionListener(e -> chooseFolder());
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            new JLabel(Messages.getString("receive.folder")), folder, browse));
    panel.add(row("receive.max.associations", maxAssociations));
    panel.add(row("receive.max.file", maxFileMb));
    panel.add(GuiUtils.getFlowLayoutPanel(ITEM_SEPARATOR_SMALL, ITEM_SEPARATOR, includeName));
    panel.add(
        GuiUtils.getFlowLayoutPanel(new JLabel(Messages.getString("receive.include.name.hint"))));
    panel.add(
        GuiUtils.getFlowLayoutPanel(
            new JLabel(Messages.getString("receive.status") + ": "), status));
    return panel;
  }

  private static JPanel row(String labelKey, java.awt.Component field) {
    return GuiUtils.getFlowLayoutPanel(
        new JLabel(Messages.getString(labelKey)), (javax.swing.JComponent) field);
  }

  private JPanel buildSendersPanel() {
    JPanel panel = GuiUtils.getVerticalBoxLayoutPanel();
    panel.setBorder(GuiUtils.getTitledBorder(Messages.getString("receive.senders")));
    JScrollPane scroll = new JScrollPane(sendersTable);
    scroll.setPreferredSize(GuiUtils.getDimension(420, 90));
    panel.add(scroll);
    JButton add = new JButton(Messages.getString("receive.add"));
    add.addActionListener(e -> sendersModel.addRow(new Object[] {"", ""}));
    JButton remove = new JButton(Messages.getString("receive.remove"));
    remove.addActionListener(e -> removeSelectedSenders());
    panel.add(GuiUtils.getFlowLayoutPanel(add, remove));
    return panel;
  }

  private JPanel buildStudiesPanel() {
    JPanel panel = GuiUtils.getVerticalBoxLayoutPanel();
    panel.setBorder(GuiUtils.getTitledBorder(Messages.getString("receive.studies")));
    panel.add(GuiUtils.getFlowLayoutPanel(total));
    JScrollPane scroll = new JScrollPane(studiesTable);
    scroll.setPreferredSize(GuiUtils.getDimension(420, 130));
    panel.add(scroll);
    JButton show = new JButton(Messages.getString("receive.show"));
    show.addActionListener(e -> showSelected());
    JButton open = new JButton(Messages.getString("receive.open.folder"));
    open.addActionListener(e -> openFolder());
    JButton delete = new JButton(Messages.getString("receive.delete"));
    delete.addActionListener(e -> deleteSelected());
    JButton refresh = new JButton(Messages.getString("receive.refresh"));
    refresh.addActionListener(e -> refreshStudies());
    panel.add(GuiUtils.getFlowLayoutPanel(show, open, delete, refresh));
    return panel;
  }

  private void show(ReceiveSettings s) {
    enabled.setSelected(s.enabled());
    aeTitle.setText(s.aeTitle());
    port.setValue(s.port());
    listenAddress.setText(s.listenAddress());
    folder.setText(s.storageFolder().toString());
    maxAssociations.setValue(s.maxAssociations());
    maxFileMb.setValue((int) Math.min(s.maxFileSizeMb(), MAX_FILE_MB));
    includeName.setSelected(s.includeNameInFileNames());
    sendersModel.setRowCount(0);
    for (AllowedSender sender : s.allowedSenders()) {
      sendersModel.addRow(new Object[] {sender.aeTitle(), sender.ipAddress()});
    }
  }

  private ReceiveSettings read() {
    if (sendersTable.isEditing()) {
      sendersTable.getCellEditor().stopCellEditing();
    }
    List<AllowedSender> senders = new ArrayList<>();
    for (int i = 0; i < sendersModel.getRowCount(); i++) {
      String ae = String.valueOf(sendersModel.getValueAt(i, 0)).trim();
      String ip = String.valueOf(sendersModel.getValueAt(i, 1)).trim();
      if (!ae.isEmpty() || !ip.isEmpty()) {
        senders.add(new AllowedSender(ae, ip));
      }
    }
    String folderText = folder.getText().trim();
    return new ReceiveSettings(
        enabled.isSelected(),
        aeTitle.getText().trim(),
        (Integer) port.getValue(),
        listenAddress.getText(),
        senders,
        folderText.isEmpty() ? ReceiveSettings.defaultStorageFolder() : Path.of(folderText),
        (Integer) maxAssociations.getValue(),
        ((Integer) maxFileMb.getValue()) * 1024L * 1024L,
        includeName.isSelected());
  }

  @Override
  public void closeAdditionalWindow() {
    Optional<ReceiveService> service = ReceiveService.get();
    if (service.isEmpty()) {
      return;
    }
    ReceiveSettings settings;
    try {
      settings = read();
    } catch (java.nio.file.InvalidPathException e) {
      showError(Messages.getString("receive.start.folder"));
      return;
    }
    Optional<String> error = settings.firstError();
    if (error.isPresent()) {
      showError(Messages.getString(error.get()));
      return;
    }
    if (!settings.equals(service.get().settings())) {
      service
          .get()
          .apply(
              settings,
              () -> {
                refreshStatus();
                refreshStudies();
              });
    }
  }

  @Override
  public void resetToDefaultValues() {
    show(ReceiveSettings.defaults());
  }

  private void refreshStatus() {
    Optional<ReceiveService> service = ReceiveService.get();
    if (service.isEmpty()) {
      status.setText(Messages.getString("receive.status.off"));
      return;
    }
    ReceiveService s = service.get();
    ReceiveSettings current = s.settings();
    if (s.startError().isPresent()) {
      status.setText(
          Messages.getString("receive.status.failed")
              .formatted(Messages.getString(s.startError().get())));
    } else if (!s.isRunning()) {
      status.setText(Messages.getString("receive.status.off"));
    } else if (current.allowedSenders().isEmpty()) {
      status.setText(Messages.getString("receive.status.no.senders"));
    } else {
      status.setText(
          Messages.getString("receive.status.running")
              .formatted(current.aeTitle(), current.port()));
    }
  }

  private void refreshStudies() {
    Optional<ReceiveService> service = ReceiveService.get();
    if (service.isEmpty()) {
      return;
    }
    StudyStore store = service.get().store();
    new SwingWorker<List<StoredStudy>, Void>() {
      @Override
      protected List<StoredStudy> doInBackground() throws IOException {
        return store.listStudies();
      }

      @Override
      protected void done() {
        try {
          showStudies(get());
        } catch (Exception e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          LOGGER.warn("Cannot list the received studies: {}", e.getClass().getSimpleName());
          showStudies(List.of());
        }
      }
    }.execute();
  }

  private void showStudies(List<StoredStudy> list) {
    studies = list;
    studiesModel.setRowCount(0);
    long bytes = 0;
    for (StoredStudy study : list) {
      bytes += study.sizeBytes();
      studiesModel.addRow(
          new Object[] {
            study.folder().getFileName().toString(),
            study.images(),
            FileUtil.humanReadableByte(study.sizeBytes(), false)
          });
    }
    total.setText(
        Messages.getString("receive.studies.total")
            .formatted(list.size(), FileUtil.humanReadableByte(bytes, false)));
  }

  private List<StoredStudy> selectedStudies() {
    List<StoredStudy> selected = new ArrayList<>();
    for (int row : studiesTable.getSelectedRows()) {
      selected.add(studies.get(studiesTable.convertRowIndexToModel(row)));
    }
    return selected;
  }

  private void showSelected() {
    ReceiveService.get().ifPresent(s -> selectedStudies().forEach(s::showStudyFolder));
  }

  private void openFolder() {
    List<StoredStudy> selected = selectedStudies();
    ReceiveService.get()
        .ifPresent(
            s ->
                GuiUtils.openSystemExplorer(
                    this,
                    selected.isEmpty()
                        ? s.settings().storageFolder()
                        : selected.getFirst().folder()));
  }

  private void deleteSelected() {
    List<StoredStudy> selected = selectedStudies();
    Optional<ReceiveService> service = ReceiveService.get();
    if (selected.isEmpty() || service.isEmpty()) {
      return;
    }
    int answer =
        JOptionPane.showConfirmDialog(
            this,
            Messages.getString("receive.delete.confirm").formatted(selected.size()),
            Messages.getString("receive.delete"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (answer != JOptionPane.OK_OPTION) {
      return;
    }
    service
        .get()
        .deleteStudies(
            selected,
            deleted -> {
              if (!deleted) {
                showError(Messages.getString("receive.delete.failed"));
              }
              refreshStudies();
            });
  }

  private void removeSelectedSenders() {
    int[] rows = sendersTable.getSelectedRows();
    for (int i = rows.length - 1; i >= 0; i--) {
      sendersModel.removeRow(sendersTable.convertRowIndexToModel(rows[i]));
    }
  }

  private void chooseFolder() {
    JFileChooser chooser = new JFileChooser(folder.getText());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      folder.setText(chooser.getSelectedFile().getPath());
    }
  }

  private void showError(String message) {
    JOptionPane.showMessageDialog(
        this, message, Messages.getString("receive.title"), JOptionPane.ERROR_MESSAGE);
  }
}
