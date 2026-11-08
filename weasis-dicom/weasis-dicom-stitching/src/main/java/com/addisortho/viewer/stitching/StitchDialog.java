/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The stitch window (design section 4): the user ticks and orders the pieces and confirms the
 * acquisition, projection, position and leg; Line up matches the seams; each seam can be inspected
 * and corrected; Save writes the draft into the study and opens it.
 */
final class StitchDialog extends JDialog {

  private static final Logger LOGGER = LoggerFactory.getLogger(StitchDialog.class);
  private static final String CHOOSE = Messages.getString("StitchDialog.choose");

  private final StitchService.Study study;
  private final List<Row> rows = new ArrayList<>();
  private final PieceModel pieceModel = new PieceModel();
  private final SeamModel seamModel = new SeamModel();
  private final JTable pieceTable = new JTable(pieceModel);
  private final JTable seamTable = new JTable(seamModel);
  private final JCheckBox oneAcquisition =
      new JCheckBox(Messages.getString("StitchDialog.oneAcquisition"));
  private final JComboBox<String> projection = new JComboBox<>(new String[] {CHOOSE, "AP", "PA"});
  private final JComboBox<String> position =
      new JComboBox<>(
          new String[] {
            CHOOSE,
            Messages.getString("StitchDialog.standing"),
            Messages.getString("StitchDialog.supine")
          });
  private final JComboBox<Object> leg = new JComboBox<>();
  private final JComboBox<Object> band = new JComboBox<>();
  private final JButton up = new JButton(Messages.getString("StitchDialog.up"));
  private final JButton down = new JButton(Messages.getString("StitchDialog.down"));
  private final JButton lineUp = new JButton(Messages.getString("StitchDialog.lineUp"));
  private final JButton inspect = new JButton(Messages.getString("StitchDialog.inspect"));
  private final JButton save = new JButton(Messages.getString("StitchDialog.save"));
  private final JButton cancel = new JButton(Messages.getString("StitchDialog.cancel"));
  private final JProgressBar progress = new JProgressBar();
  private final JLabel state = new JLabel(" ");
  private final ImagePanel preview = new ImagePanel(null);
  private final JLabel previewCaption = new JLabel(" ");
  private SwingWorker<Void, Loaded> pictures;
  private boolean showingStitch;

  private List<Piece> chosen = List.of();
  private List<Seam> seams = List.of();
  private SwingWorker<?, ?> running;
  // background work still executing (a cancelled worker is "done" before its loop has stopped)
  private final AtomicInteger active = new AtomicInteger();
  // bumped whenever pieces, order or band change: a job's results from an older generation are
  // dropped
  private int generation;

  /** A row of the pieces table. */
  private static final class Row {
    final StitchService.Candidate candidate;
    boolean use;
    PiecePicture picture;
    Icon thumb;
    boolean pictureFailed;

    Row(StitchService.Candidate candidate) {
      this.candidate = candidate;
    }
  }

  /** A row's picture, or null when the image could not be read. */
  private record Loaded(Row row, PiecePicture picture) {}

  StitchDialog(Window owner, StitchService.Study study) {
    super(owner, Messages.getString("StitchDialog.title"), ModalityType.MODELESS);
    this.study = study;
    List<StitchService.Candidate> sorted = new ArrayList<>(study.candidates());
    // a weak first guess only (design section 5.1): the user puts the pieces in order
    sorted.sort(
        java.util.Comparator.comparing(
                (StitchService.Candidate c) -> c.piece().header.getString(Tag.AcquisitionTime, ""))
            .thenComparingInt(c -> c.piece().header.getInt(Tag.InstanceNumber, 0)));
    sorted.forEach(c -> rows.add(new Row(c)));

    leg.addItem(CHOOSE);
    Arrays.stream(Stitch.Leg.values()).forEach(leg::addItem);
    band.addItem(CHOOSE);
    band.addItem(SeamMatcher.Band.WHOLE);
    band.addItem(SeamMatcher.Band.LEFT_HALF);
    band.addItem(SeamMatcher.Band.RIGHT_HALF);

    setContentPane(layoutContent());
    wire();
    refresh();
    setPreferredSize(new Dimension(1100, 760));
    pack();
    setLocationRelativeTo(owner);
    if (!rows.isEmpty()) {
      pieceTable.setRowSelectionInterval(0, 0);
    }
    loadPictures();
  }

  // one image at a time, in the background: the window is usable while the pictures arrive
  private void loadPictures() {
    List<Row> all = List.copyOf(rows);
    pictures =
        new SwingWorker<>() {
          @Override
          protected Void doInBackground() {
            for (Row row : all) {
              if (isCancelled()) {
                break;
              }
              try {
                publish(new Loaded(row, PiecePicture.of(row.candidate.piece())));
              } catch (IOException | RuntimeException | OutOfMemoryError e) {
                LOGGER.warn("No picture for a stitch piece", e);
                publish(new Loaded(row, null));
              }
            }
            return null;
          }

          @Override
          protected void process(List<Loaded> chunks) {
            for (Loaded done : chunks) {
              Row row = done.row();
              row.picture = done.picture();
              row.pictureFailed = done.picture() == null;
              row.thumb = done.picture() == null ? null : new ImageIcon(done.picture().thumb());
              int i = rows.indexOf(row);
              pieceModel.fireTableRowsUpdated(i, i);
              if (i == pieceTable.getSelectedRow() && !showingStitch) {
                showSelectedPiece();
              }
            }
          }
        };
    pictures.execute();
  }

  @Override
  public void dispose() {
    if (pictures != null) {
      pictures.cancel(false);
    }
    super.dispose();
  }

  private void showSelectedPiece() {
    int i = pieceTable.getSelectedRow();
    if (i < 0 || i >= rows.size()) {
      return;
    }
    Row row = rows.get(i);
    String label = row.candidate.piece().label();
    showingStitch = false;
    preview.setImage(row.picture == null ? null : row.picture.large(), null);
    previewCaption.setText(
        Messages.getString(
                row.pictureFailed ? "StitchDialog.previewNoPicture" : "StitchDialog.previewPiece")
            .formatted(label));
  }

  private static String html(String s) {
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  // the image column: the label, then whatever else the file says, so the rows can be told apart
  private static String imageCell(StitchService.Candidate c) {
    StringBuilder s = new StringBuilder("<html><b>").append(html(c.piece().label())).append("</b>");
    if (!c.fileName().isEmpty()) {
      s.append("<br>")
          .append(html(Messages.getString("StitchDialog.file").formatted(c.fileName())));
    }
    List<String> details = c.piece().details();
    if (!details.isEmpty()) {
      s.append("<br>").append(html(String.join(", ", details)));
    }
    return s.append("</html>").toString();
  }

  private JPanel layoutContent() {
    pieceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    pieceTable.setRowHeight(PiecePicture.THUMB_PX + 6);
    pieceTable.getColumnModel().getColumn(0).setMaxWidth(50);
    pieceTable.getColumnModel().getColumn(1).setMinWidth(PiecePicture.THUMB_PX + 16);
    pieceTable.getColumnModel().getColumn(1).setMaxWidth(PiecePicture.THUMB_PX + 16);
    pieceTable
        .getColumnModel()
        .getColumn(1)
        .setCellRenderer(
            new DefaultTableCellRenderer() {
              @Override
              protected void setValue(Object value) {
                setHorizontalAlignment(CENTER);
                setIcon(value instanceof Icon icon ? icon : null);
                setText(value instanceof Icon ? "" : String.valueOf(value));
              }
            });
    pieceTable.getColumnModel().getColumn(2).setPreferredWidth(300);
    pieceTable.getColumnModel().getColumn(3).setPreferredWidth(240);
    seamTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    up.addActionListener(e -> moveSelected(-1));
    down.addActionListener(e -> moveSelected(1));
    JPanel order = new JPanel(new FlowLayout(FlowLayout.LEFT));
    order.add(up);
    order.add(down);

    JPanel pieces = new JPanel(new BorderLayout(0, 4));
    pieces.add(SeamEditor.text(Messages.getString("StitchDialog.intro"), 2), BorderLayout.NORTH);
    JScrollPane pieceScroll = new JScrollPane(pieceTable);
    pieceScroll.setPreferredSize(new Dimension(640, 2 * (PiecePicture.THUMB_PX + 6) + 30));
    pieces.add(pieceScroll, BorderLayout.CENTER);
    JPanel piecesSouth = new JPanel(new BorderLayout());
    piecesSouth.add(order, BorderLayout.NORTH);
    if (!study.excluded().isEmpty()) {
      piecesSouth.add(
          SeamEditor.text(
              Messages.getString("StitchDialog.excluded")
                  .formatted(String.join("; ", study.excluded())),
              2),
          BorderLayout.SOUTH);
    }
    pieces.add(piecesSouth, BorderLayout.SOUTH);

    JPanel confirm = new JPanel(new GridBagLayout());
    confirm.setBorder(
        BorderFactory.createTitledBorder(Messages.getString("StitchDialog.lineUpHint")));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 6;
    confirm.add(oneAcquisition, c);
    c.gridwidth = 1;
    c.gridy = 1;
    confirm.add(new JLabel(Messages.getString("StitchDialog.projection")), c);
    c.gridx = 1;
    confirm.add(projection, c);
    c.gridx = 2;
    confirm.add(new JLabel(Messages.getString("StitchDialog.position")), c);
    c.gridx = 3;
    confirm.add(position, c);
    c.gridx = 4;
    confirm.add(new JLabel(Messages.getString("StitchDialog.leg")), c);
    c.gridx = 5;
    confirm.add(leg, c);
    c.gridx = 0;
    c.gridy = 2;
    confirm.add(new JLabel(Messages.getString("StitchDialog.band")), c);
    c.gridx = 1;
    c.gridwidth = 3;
    confirm.add(band, c);
    c.gridwidth = 1;
    c.gridx = 5;
    confirm.add(lineUp, c);

    JPanel seamPanel = new JPanel(new BorderLayout(0, 4));
    seamPanel.add(new JScrollPane(seamTable), BorderLayout.CENTER);
    JPanel seamButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
    seamButtons.add(inspect);
    seamPanel.add(seamButtons, BorderLayout.SOUTH);
    seamPanel.setPreferredSize(new Dimension(640, 150));

    // the pieces take the spare height, so that all the pictures show on a tall window
    JPanel lower = new JPanel();
    lower.setLayout(new BoxLayout(lower, BoxLayout.Y_AXIS));
    lower.add(confirm);
    lower.add(Box.createVerticalStrut(8));
    lower.add(seamPanel);
    JPanel left = new JPanel(new BorderLayout(0, 8));
    left.add(pieces, BorderLayout.CENTER);
    left.add(lower, BorderLayout.SOUTH);

    preview.setPreferredSize(new Dimension(320, 640));
    JPanel previewPanel = new JPanel(new BorderLayout(0, 4));
    previewPanel.add(previewCaption, BorderLayout.NORTH);
    previewPanel.add(preview, BorderLayout.CENTER);
    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, previewPanel);
    split.setResizeWeight(0.7);

    JPanel south = new JPanel(new BorderLayout(0, 4));
    south.add(SeamEditor.text(Messages.getString("StitchDialog.warning"), 2), BorderLayout.NORTH);
    JPanel status = new JPanel(new BorderLayout(6, 0));
    status.add(state, BorderLayout.CENTER);
    status.add(progress, BorderLayout.EAST);
    south.add(status, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JButton close = new JButton(Messages.getString("StitchDialog.close"));
    close.addActionListener(e -> dispose());
    buttons.add(cancel);
    buttons.add(save);
    buttons.add(close);
    south.add(buttons, BorderLayout.SOUTH);

    JPanel root = new JPanel(new BorderLayout(0, 8));
    root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    root.add(split, BorderLayout.CENTER);
    root.add(south, BorderLayout.SOUTH);
    return root;
  }

  private void wire() {
    oneAcquisition.addActionListener(e -> refresh());
    projection.addActionListener(e -> refresh());
    position.addActionListener(e -> refresh());
    leg.addActionListener(e -> refresh());
    band.addActionListener(e -> discardSeams());
    lineUp.addActionListener(e -> lineUp());
    inspect.addActionListener(e -> inspectSelected());
    save.addActionListener(e -> save());
    cancel.addActionListener(
        e -> {
          if (running != null) {
            running.cancel(false);
          }
        });
    seamTable.getSelectionModel().addListSelectionListener(e -> refresh());
    pieceTable
        .getSelectionModel()
        .addListSelectionListener(
            e -> {
              if (!e.getValueIsAdjusting()) {
                showSelectedPiece();
              }
            });
    seamTable.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2) {
              inspectSelected();
            } else if (!showingStitch) {
              showPreview();
            }
          }
        });
  }

  private List<Piece> ticked() {
    return rows.stream().filter(r -> r.use).map(r -> r.candidate.piece()).toList();
  }

  private boolean confirmed() {
    return oneAcquisition.isSelected()
        && projection.getSelectedIndex() > 0
        && position.getSelectedIndex() > 0
        && leg.getSelectedItem() instanceof Stitch.Leg
        && band.getSelectedItem() instanceof SeamMatcher.Band;
  }

  private boolean busy() {
    return active.get() > 0 || (running != null && !running.isDone());
  }

  private Stitch.Choices choices() {
    return new Stitch.Choices(
        (String) projection.getSelectedItem(),
        ((String) position.getSelectedItem()).toLowerCase(Locale.ROOT),
        (Stitch.Leg) leg.getSelectedItem(),
        (SeamMatcher.Band) band.getSelectedItem());
  }

  // the seams belong to one set of pieces in one order: any change there discards them
  private void discardSeams() {
    generation++;
    seams = List.of();
    chosen = List.of();
    seamModel.fireTableDataChanged();
    showSelectedPiece();
    refresh();
  }

  private void refresh() {
    boolean busy = busy();
    List<Piece> ticked = ticked();
    int n = ticked.size();
    boolean mixed = Stitch.mixesScaledAndUnscaled(ticked);
    lineUp.setEnabled(!busy && n >= 2 && n <= 4 && confirmed() && !mixed);
    up.setEnabled(!busy);
    down.setEnabled(!busy);
    if (mixed && !busy) {
      state.setText(Messages.getString("StitchDialog.mixedScale"));
    }
    boolean lined = !seams.isEmpty() && seams.size() == chosen.size() - 1;
    inspect.setEnabled(!busy && lined && seamTable.getSelectedRow() >= 0);
    save.setEnabled(
        !busy
            && lined
            && confirmed()
            && seams.stream().noneMatch(s -> s.status() == Seam.Status.FAILED));
    cancel.setEnabled(busy);
    if (!busy && lined && seams.stream().anyMatch(s -> s.status() == Seam.Status.FAILED)) {
      state.setText(Messages.getString("StitchDialog.failedSeams"));
    }
  }

  private void moveSelected(int delta) {
    int i = pieceTable.getSelectedRow();
    int j = i + delta;
    if (busy() || i < 0 || j < 0 || j >= rows.size()) {
      return;
    }
    rows.add(j, rows.remove(i));
    pieceModel.fireTableDataChanged();
    pieceTable.setRowSelectionInterval(j, j);
    discardSeams();
  }

  private void lineUp() {
    List<Piece> pieces = ticked();
    SeamMatcher.Band on = (SeamMatcher.Band) band.getSelectedItem();
    int job = generation;
    running =
        new SwingWorker<List<Seam>, String>() {
          @Override
          protected List<Seam> doInBackground() throws Exception {
            active.incrementAndGet();
            try {
              return work();
            } finally {
              active.decrementAndGet();
              SwingUtilities.invokeLater(StitchDialog.this::refresh);
            }
          }

          private List<Seam> work() throws Exception {
            for (int i = 0; i < pieces.size() && !isCancelled(); i++) {
              publish(Messages.getString("StitchDialog.preparing").formatted(i + 1, pieces.size()));
              setProgress(100 * i / (2 * pieces.size() - 1));
              pieces.get(i).prepare();
            }
            List<Seam> out = new ArrayList<>();
            for (int i = 0; i + 1 < pieces.size() && !isCancelled(); i++) {
              publish(
                  Messages.getString("StitchDialog.matching").formatted(i + 1, pieces.size() - 1));
              setProgress(100 * (pieces.size() + i) / (2 * pieces.size() - 1));
              out.add(SeamMatcher.match(pieces.get(i).match(), pieces.get(i + 1).match(), on));
            }
            return out;
          }

          @Override
          protected void process(List<String> chunks) {
            state.setText(chunks.get(chunks.size() - 1));
          }

          @Override
          protected void done() {
            progress.setValue(0);
            if (job != generation) {
              refresh(); // the pieces, their order or the band changed meanwhile
              return;
            }
            try {
              chosen = pieces;
              seams = new ArrayList<>(get());
              state.setText(" ");
              seamModel.fireTableDataChanged();
              if (!seams.isEmpty()) {
                seamTable.setRowSelectionInterval(0, 0);
              }
              showPreview();
            } catch (CancellationException e) {
              discardSeams();
            } catch (Exception e) {
              fail(e);
            }
            refresh();
          }
        };
    running.addPropertyChangeListener(
        e -> {
          if ("progress".equals(e.getPropertyName())) {
            progress.setValue((Integer) e.getNewValue());
          }
        });
    running.execute();
    refresh();
  }

  private void inspectSelected() {
    int i = seamTable.getSelectedRow();
    if (i < 0 || i >= seams.size()) {
      return;
    }
    SeamEditor editor =
        new SeamEditor(
            this,
            i,
            chosen.get(i),
            chosen.get(i + 1),
            seams.get(i),
            (SeamMatcher.Band) band.getSelectedItem());
    editor.setVisible(true);
    editor
        .result()
        .ifPresent(
            s -> {
              seams.set(i, s);
              seamModel.fireTableRowsUpdated(i, i);
              showPreview();
              refresh();
            });
  }

  private Stitch stitch() {
    return new Stitch(chosen, seams, choices());
  }

  private void showPreview() {
    if (seams.isEmpty() || !confirmed()) {
      return;
    }
    Stitch s = stitch();
    Gray g = new Compositor(s).preview(MatchImage.COARSE_MM, false).normalized();
    double[] b = s.bounds();
    double[] ys = new double[s.seams.size()];
    for (int i = 0; i < ys.length; i++) {
      ys[i] = (s.seamY(i) - b[1]) / g.mm();
    }
    preview.setImage(
        ImagePanel.gray(g.data(), g.width(), g.height()),
        (g2, scale) -> {
          g2.setStroke(
              new BasicStroke(
                  1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 1f, new float[] {6, 4}, 0));
          for (int i = 0; i < ys.length; i++) {
            g2.setColor(
                seams.get(i).status() == Seam.Status.FAILED ? Color.RED : new Color(0xA8FF30));
            g2.draw(new Line2D.Double(0, ys[i] * scale, g.width() * scale, ys[i] * scale));
            g2.drawString(Integer.toString(i + 1), 4f, (float) (ys[i] * scale) - 4);
          }
        });
    showingStitch = true;
    previewCaption.setText(Messages.getString("StitchDialog.previewStitch"));
    s.noScaleReason()
        .ifPresent(r -> state.setText(Messages.getString("StitchDialog.noScale").formatted(r)));
  }

  private void save() {
    Stitch s = stitch();
    running =
        new SwingWorker<Path, Void>() {
          @Override
          protected Path doInBackground() throws Exception {
            active.incrementAndGet();
            try {
              return StitchService.save(
                  s,
                  (done, total) -> {
                    setProgress(100 * done / Math.max(1, total));
                    return !isCancelled();
                  });
            } finally {
              active.decrementAndGet();
              SwingUtilities.invokeLater(StitchDialog.this::refresh);
            }
          }

          @Override
          protected void done() {
            progress.setValue(0);
            try {
              Path file = get();
              String uid = seriesUid(file);
              StitchService.loadAndOpen(study.model(), file, uid);
              state.setText(Messages.getString("StitchDialog.saved"));
            } catch (CancellationException e) {
              state.setText(" ");
            } catch (Exception e) {
              fail(e);
            }
            refresh();
          }
        };
    running.addPropertyChangeListener(
        e -> {
          if ("progress".equals(e.getPropertyName())) {
            progress.setValue((Integer) e.getNewValue());
          }
        });
    state.setText(Messages.getString("StitchDialog.saving"));
    running.execute();
    refresh();
  }

  private static String seriesUid(Path file) throws java.io.IOException {
    try (var in = new org.dcm4che3.io.DicomInputStream(file.toFile())) {
      in.setIncludeBulkData(org.dcm4che3.io.DicomInputStream.IncludeBulkData.NO);
      return in.readDataset().getString(Tag.SeriesInstanceUID);
    }
  }

  private void fail(Exception e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    LOGGER.error("Stitching failed", cause);
    state.setText(" ");
    JOptionPane.showMessageDialog(
        this,
        Messages.getString("StitchDialog.error").formatted(cause.getMessage()),
        Messages.getString("StitchDialog.title"),
        JOptionPane.ERROR_MESSAGE);
  }

  private final class PieceModel extends AbstractTableModel {
    private final String[] names = {
      Messages.getString("StitchDialog.use"),
      Messages.getString("StitchDialog.picture"),
      Messages.getString("StitchDialog.image"),
      Messages.getString("StitchDialog.notes")
    };

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return names.length;
    }

    @Override
    public String getColumnName(int c) {
      return names[c];
    }

    @Override
    public Class<?> getColumnClass(int c) {
      return c == 0 ? Boolean.class : String.class;
    }

    @Override
    public boolean isCellEditable(int r, int c) {
      return c == 0 && !busy();
    }

    @Override
    public Object getValueAt(int r, int c) {
      Row row = rows.get(r);
      return switch (c) {
        case 0 -> row.use;
        case 1 ->
            row.thumb != null
                ? row.thumb
                : Messages.getString(
                    row.pictureFailed ? "StitchDialog.noPicture" : "StitchDialog.reading");
        case 2 -> imageCell(row.candidate);
        default -> "<html>" + html(String.join("; ", row.candidate.notes())) + "</html>";
      };
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
      rows.get(r).use = Boolean.TRUE.equals(v);
      discardSeams();
    }
  }

  private final class SeamModel extends AbstractTableModel {
    private final String[] names = {
      Messages.getString("StitchDialog.seam"),
      Messages.getString("StitchDialog.method"),
      Messages.getString("StitchDialog.status"),
      Messages.getString("StitchDialog.residual"),
      Messages.getString("StitchDialog.uncertainty"),
      Messages.getString("StitchDialog.notes")
    };

    @Override
    public int getRowCount() {
      return seams.size();
    }

    @Override
    public int getColumnCount() {
      return names.length;
    }

    @Override
    public String getColumnName(int c) {
      return names[c];
    }

    @Override
    public Object getValueAt(int r, int c) {
      Seam s = seams.get(r);
      return switch (c) {
        case 0 -> "%d to %d".formatted(r + 1, r + 2);
        case 1 -> s.method().word;
        case 2 -> s.status().word;
        case 3 -> Double.isNaN(s.rmsMm()) ? "" : String.format(Locale.ROOT, "%.2f mm", s.rmsMm());
        case 4 ->
            Double.isNaN(s.rotationSigmaDeg())
                ? ""
                : String.format(
                    Locale.ROOT, "%.2f deg, %.2f mm", s.rotationSigmaDeg(), s.shiftSigmaMm());
        default -> String.join("; ", s.reasons());
      };
    }
  }
}
