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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import org.weasis.dicom.explorer.library.StoredInstance;

/**
 * The stitch window (design section 4): the user ticks and orders the pieces and confirms the
 * acquisition, projection, position and leg; Line up matches the seams; each seam can be inspected
 * and corrected; Save writes the draft into the study and opens it. Where the viewer keeps stitch
 * projects ({@link Host#projects}), Save also keeps the stitch as an editable version and Edit
 * stitch reopens one: the same window on the same pieces, every seam checked again, saved as a new
 * version under the one it came from.
 */
final class StitchDialog extends JDialog {

  private static final Logger LOGGER = LoggerFactory.getLogger(StitchDialog.class);
  private static final String CHOOSE = Messages.getString("StitchDialog.choose");
  private static final DateTimeFormatter SAVED_AT =
      DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss", Locale.ENGLISH)
          .withZone(ZoneId.systemDefault());

  /** What the window asks of the viewer around it; a test puts its own in its place. */
  interface Host {
    /** Where editable versions are kept, or null: then the window has no Edit stitch. */
    StitchProjects projects();

    /** Saves the picture for good and, where versions are kept, the stitch as a new version. */
    StitchService.Saved save(
        Stitch s,
        List<SeamRecipe> recipes,
        StitchResume.Lineage lineage,
        Compositor.Progress progress)
        throws IOException;

    /** Loads the saved picture into the study and opens it. */
    void open(StoredInstance saved, Runnable notOpened);

    /** Shows a message the user must close; {@code type} as in {@link JOptionPane}. */
    void tell(String message, int type);

    /** Asks which of {@code options}; -1 when the user chose none. */
    int choose(String question, String[] options);
  }

  private final StitchService.Study study;
  private final Host host;
  private final StitchProjects projects;
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
  private final JButton edit = new JButton(Messages.getString("StitchDialog.edit"));
  private final JButton saveUnverified =
      new JButton(Messages.getString("StitchDialog.saveUnverified"));
  private final JButton cancel = new JButton(Messages.getString("StitchDialog.cancel"));
  private final JProgressBar progress = new JProgressBar();
  private final JLabel state = new JLabel(" ");
  private final ImagePanel preview = new ImagePanel(this::previewPressed);
  // the cut rows the user dragged, per seam, in the upper piece's mm; NaN: automatic
  private double[] cuts = new double[0];
  // while a seam line is dragged in the preview: which seam, and the preview's mm per pixel
  private int draggedSeam = -1;
  private double[] previewSeamPx = new double[0];
  private double previewMm = 1;
  private final JLabel previewCaption = new JLabel(" ");
  private SwingWorker<Void, Loaded> pictures;
  private boolean showingStitch;

  private List<Piece> chosen = List.of();

  // a press on a seam line of the stitch preview picks it up; a drag then moves its cut
  private void previewPressed(double px, double py) {
    draggedSeam = -1;
    if (!showingStitch) {
      return;
    }
    for (int i = 0; i < previewSeamPx.length; i++) {
      if (Math.abs(previewSeamPx[i] - py) <= 8) {
        draggedSeam = i;
      }
    }
  }

  private void previewDragged(double dx, double dy) {
    if (draggedSeam < 0 || draggedSeam >= seams.size() || busy()) {
      return;
    }
    cuts[draggedSeam] = stitch().cut(draggedSeam).yMm() + dy * previewMm;
    showPreview();
  }

  private List<Seam> seams = List.of();
  // how each seam was set, in step with seams: what a saved version makes them again from
  private List<SeamRecipe> recipes = List.of();
  // the project and version the next save continues: the one reopened, then each one saved here
  private StitchResume.Lineage lineage = StitchResume.Lineage.NEW;
  // the versions saved for this study, oldest first; filled in the background
  private List<StitchProjects.Listed> versions = List.of();
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
    this(owner, study, null);
  }

  /**
   * @param host null for the viewer itself
   */
  StitchDialog(Window owner, StitchService.Study study, Host host) {
    super(owner, Messages.getString("StitchDialog.title"), ModalityType.MODELESS);
    this.study = study;
    this.host = host == null ? new ViewerHost() : host;
    this.projects = this.host.projects();
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
    preview.setOnDrag(this::previewDragged);
    wire();
    refresh();
    setPreferredSize(new Dimension(1100, 760));
    pack();
    setLocationRelativeTo(owner);
    if (!rows.isEmpty()) {
      pieceTable.setRowSelectionInterval(0, 0);
    }
    loadPictures();
    loadVersions();
  }

  /** The viewer: the study library, the explorer's loader and ordinary message boxes. */
  private final class ViewerHost implements Host {
    private final StitchProjects kept =
        StitchService.projectStore().map(StitchProjects::new).orElse(null);

    @Override
    public StitchProjects projects() {
      return kept;
    }

    @Override
    public StitchService.Saved save(
        Stitch s, List<SeamRecipe> how, StitchResume.Lineage from, Compositor.Progress progress)
        throws IOException {
      return StitchService.save(kept, s, how, from, progress);
    }

    @Override
    public void open(StoredInstance saved, Runnable notOpened) {
      StitchService.loadAndOpen(study.model(), saved.file(), saved.seriesUid(), notOpened);
    }

    @Override
    public void tell(String message, int type) {
      JOptionPane.showMessageDialog(
          StitchDialog.this, message, Messages.getString("StitchDialog.title"), type);
    }

    @Override
    public int choose(String question, String[] options) {
      Object picked =
          JOptionPane.showInputDialog(
              StitchDialog.this,
              question,
              Messages.getString("StitchDialog.edit"),
              JOptionPane.QUESTION_MESSAGE,
              null,
              options,
              options[0]);
      return picked == null ? -1 : Arrays.asList(options).indexOf(picked);
    }
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
    // the notes column is cut short; the full reasons, one per line, show on hover
    seamTable
        .getColumnModel()
        .getColumn(5)
        .setCellRenderer(
            new DefaultTableCellRenderer() {
              @Override
              public java.awt.Component getTableCellRendererComponent(
                  JTable t, Object value, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                setToolTipText(row < seams.size() ? reasonsHtml(seams.get(row)) : null);
                return this;
              }
            });
    seamTable.getColumnModel().getColumn(5).setPreferredWidth(360);

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
    if (projects != null) {
      edit.setToolTipText(Messages.getString("StitchDialog.editTip"));
      buttons.add(edit);
    }
    buttons.add(cancel);
    buttons.add(saveUnverified);
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
    save.addActionListener(e -> save(stitch()));
    edit.addActionListener(e -> editStitch());
    saveUnverified.addActionListener(e -> saveUnverified());
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
    recipes = List.of();
    chosen = List.of();
    cuts = new double[0];
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
    boolean anyFailed = seams.stream().anyMatch(s -> s.status() == Seam.Status.FAILED);
    save.setEnabled(!busy && lined && confirmed() && !anyFailed);
    saveUnverified.setEnabled(!busy && lined && confirmed() && anyFailed);
    cancel.setEnabled(busy);
    edit.setEnabled(!busy && !versions.isEmpty());
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
    boolean bothLegs = leg.getSelectedItem() == Stitch.Leg.BOTH && on == SeamMatcher.Band.WHOLE;
    SeamRecipe how = bothLegs ? SeamRecipe.matchBothLegs() : SeamRecipe.match(on);
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
              out.add(how.compute(pieces.get(i).match(), pieces.get(i + 1).match()));
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
              recipes = new ArrayList<>(Collections.nCopies(seams.size(), how));
              cuts = new double[seams.size()];
              Arrays.fill(cuts, Double.NaN);
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
            new SeamRecipe.Made(recipes.get(i), seams.get(i)),
            (SeamMatcher.Band) band.getSelectedItem(),
            s -> {
              List<Seam> trial = new ArrayList<>(seams);
              trial.set(i, s);
              return new Stitch(chosen, trial, choices(), cuts);
            });
    editor.setVisible(true);
    editor.result().ifPresent(made -> useSeam(i, made));
  }

  /** Takes the seam the user settled on in the seam window, with how it was set. */
  void useSeam(int i, SeamRecipe.Made made) {
    seams.set(i, made.seam());
    recipes.set(i, made.recipe());
    seamModel.fireTableRowsUpdated(i, i);
    showPreview();
    refresh();
  }

  private Stitch stitch() {
    return new Stitch(chosen, seams, choices(), cuts);
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
    previewSeamPx = ys;
    previewMm = g.mm();
    StringBuilder cutWords = new StringBuilder();
    for (int i = 0; i < ys.length; i++) {
      cutWords.append(i == 0 ? "" : " ").append("Seam %d: %s.".formatted(i + 1, s.cut(i).note()));
    }
    preview.setToolTipText(
        "<html><body style='width:360px'>" + html(cutWords.toString()) + "</body></html>");
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

  // a failed seam may still be saved for viewing: the user is told what failed and what the
  // saved image cannot be used for, and the image says so itself (burned in, no scale)
  private void saveUnverified() {
    Stitch s = new Stitch(chosen, seams, choices(), cuts, true);
    // one HTML string without newlines: JOptionPane splits a message at newlines into separate
    // labels, and only a label that starts with <html> is laid out as HTML (7 Nov 2026: the
    // reasons came out as raw text, the box grew wider than the screen, and OK fell off it)
    StringBuilder why = new StringBuilder();
    for (int i = 0; i < seams.size(); i++) {
      if (seams.get(i).status() == Seam.Status.FAILED) {
        why.append("<br>")
            .append(html("Seam %d: %s".formatted(i + 1, seams.get(i).reasons().get(0))));
      }
    }
    int answer =
        JOptionPane.showConfirmDialog(
            this,
            Messages.getString("StitchDialog.unverifiedConfirm").formatted(why),
            Messages.getString("StitchDialog.saveUnverified"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.WARNING_MESSAGE);
    if (answer == JOptionPane.OK_OPTION) {
      save(s);
    }
  }

  private void save(Stitch s) {
    List<SeamRecipe> how = List.copyOf(recipes);
    StitchResume.Lineage from = lineage;
    running =
        new SwingWorker<StitchService.Saved, Void>() {
          @Override
          protected StitchService.Saved doInBackground() throws Exception {
            active.incrementAndGet();
            try {
              return host.save(
                  s,
                  how,
                  from,
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
              StitchService.Saved result = get();
              StoredInstance saved = result.picture().instance();
              // Only now is it saved: the file is on the disk, read back and in the catalog.
              state.setText(
                  Messages.getString(
                          s.unverified ? "StitchDialog.savedUnverified" : "StitchDialog.saved")
                      + (result.picture().catalogSaved()
                          ? ""
                          : " " + Messages.getString("StitchDialog.catalogPending"))
                      + (result.version() == null
                          ? ""
                          : " " + Messages.getString("StitchDialog.versionSaved")));
              if (result.version() != null) {
                // a further save from this window is an edit of the version just saved
                continueFrom(result.version());
                loadVersions();
              }
              host.open(saved, StitchDialog.this::notOpened);
              if (result.versionProblem() != null) {
                host.tell(
                    Messages.getString("StitchDialog.versionNotSaved")
                        .formatted(result.versionProblem()),
                    JOptionPane.WARNING_MESSAGE);
              }
            } catch (CancellationException e) {
              state.setText(" ");
            } catch (Exception e) {
              saveFailed(e);
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

  private void continueFrom(StitchProject version) {
    lineage = new StitchResume.Lineage(version.projectId(), version.versionId());
    setTitle(Messages.getString("StitchDialog.titleEditing").formatted(savedAt(version.savedAt())));
  }

  private static String savedAt(Instant when) {
    return SAVED_AT.format(when);
  }

  // the saved versions of this study, read in the background: Edit stitch is offered once there
  // is one
  private void loadVersions() {
    if (projects == null || rows.isEmpty()) {
      return;
    }
    String studyUid = rows.get(0).candidate.piece().header.getString(Tag.StudyInstanceUID, "");
    new SwingWorker<List<StitchProjects.Listed>, Void>() {
      @Override
      protected List<StitchProjects.Listed> doInBackground() throws IOException {
        return projects.list(studyUid);
      }

      @Override
      protected void done() {
        try {
          versions = get();
        } catch (Exception e) {
          // Its message can hold a path: not for the log.
          LOGGER.error("The saved stitches could not be listed: {}", e.getClass().getSimpleName());
          versions = List.of();
          state.setText(Messages.getString("StitchDialog.editListFailed"));
        }
        refresh();
      }
    }.execute();
  }

  // Edit stitch: the user picks a saved version, newest first
  private void editStitch() {
    if (busy() || versions.isEmpty()) {
      return;
    }
    List<StitchProjects.Listed> newestFirst = new ArrayList<>(versions);
    Collections.reverse(newestFirst);
    String[] options = new String[newestFirst.size()];
    for (int i = 0; i < options.length; i++) {
      options[i] = "%d. %s".formatted(options.length - i, describe(newestFirst.get(i)));
    }
    int picked = host.choose(Messages.getString("StitchDialog.editChoose"), options);
    if (picked < 0) {
      return;
    }
    StitchProjects.Listed version = newestFirst.get(picked);
    if (version.project() == null) {
      host.tell(version.problem(), JOptionPane.ERROR_MESSAGE);
    } else {
      reopen(version.project());
    }
  }

  private String describe(StitchProjects.Listed version) {
    StitchProject p = version.project();
    if (p == null) {
      return Messages.getString("StitchDialog.editUnreadable").formatted(version.problem());
    }
    List<Seam> saved = p.seams().stream().map(StitchProject.SeamEntry::result).toList();
    String words =
        Messages.getString("StitchDialog.editVersion")
            .formatted(
                savedAt(p.savedAt()),
                p.sources().size(),
                Stitch.method(saved).word,
                (p.anyFailed() ? Seam.Status.FAILED : Seam.Status.CHECK).word);
    if (p.output().unverified()) {
      words += Messages.getString("StitchDialog.editVersionUnverified");
    }
    for (StitchProjects.Listed other : versions) {
      if (other.project() != null && other.versionId().equals(p.parentVersionId())) {
        words +=
            Messages.getString("StitchDialog.editVersionChild")
                .formatted(savedAt(other.project().savedAt()));
      }
    }
    return words;
  }

  /**
   * Brings a saved version back into the window: its pieces ticked in its order, its confirmations,
   * then every seam made again in the background. Refused, with nothing changed, unless every piece
   * is here and unchanged.
   */
  private void reopen(StitchProject p) {
    List<Piece> found;
    try {
      found = StitchResume.sources(p, rows.stream().map(r -> r.candidate.piece()).toList());
    } catch (StitchProjectException e) {
      refused(e);
      return;
    }
    rows.sort(
        Comparator.comparingInt(
            r -> {
              int i = found.indexOf(r.candidate.piece());
              return i < 0 ? Integer.MAX_VALUE : i;
            }));
    rows.forEach(r -> r.use = found.contains(r.candidate.piece()));
    pieceModel.fireTableDataChanged();
    // what the user confirmed for this stitch when it was made
    oneAcquisition.setSelected(true);
    projection.setSelectedItem(p.choices().projection());
    for (int i = 1; i < position.getItemCount(); i++) {
      if (position.getItemAt(i).equalsIgnoreCase(p.choices().position())) {
        position.setSelectedIndex(i);
      }
    }
    leg.setSelectedItem(p.choices().leg());
    band.setSelectedItem(p.choices().band());
    discardSeams();
    int job = generation;
    running =
        new SwingWorker<StitchResume.Resumed, Void>() {
          @Override
          protected StitchResume.Resumed doInBackground() throws Exception {
            active.incrementAndGet();
            try {
              return StitchResume.recheck(
                  p,
                  found,
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
            if (job != generation) {
              refresh(); // the pieces, their order or the band changed meanwhile
              return;
            }
            try {
              reopened(p, get());
            } catch (CancellationException e) {
              forget();
            } catch (Exception e) {
              forget();
              if (e.getCause() instanceof StitchProjectException refusal) {
                refused(refusal);
              } else {
                reopenFailed(e);
              }
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
    state.setText(Messages.getString("StitchDialog.editChecking"));
    running.execute();
    refresh();
  }

  // a saved stitch that did not reopen leaves nothing ticked and nothing confirmed: its
  // confirmations were given for its own pieces, not for whatever is open now
  private void forget() {
    rows.forEach(r -> r.use = false);
    pieceModel.fireTableDataChanged();
    oneAcquisition.setSelected(false);
    projection.setSelectedIndex(0);
    position.setSelectedIndex(0);
    leg.setSelectedIndex(0);
    band.setSelectedIndex(0);
    discardSeams();
  }

  private void reopened(StitchProject p, StitchResume.Resumed r) {
    chosen = r.pieces();
    seams = new ArrayList<>(r.seams());
    recipes = new ArrayList<>(r.recipes());
    cuts = r.cuts().clone();
    continueFrom(p);
    seamModel.fireTableDataChanged();
    seamTable.setRowSelectionInterval(0, 0);
    state.setText(Messages.getString("StitchDialog.editOpened"));
    showPreview();
    if (!r.notes().isEmpty()) {
      host.tell(
          Messages.getString("StitchDialog.editNotes").formatted(bullets(r.notes())),
          JOptionPane.WARNING_MESSAGE);
    }
  }

  // why a saved stitch was not reopened; for a source problem, each piece and what to do
  private void refused(StitchProjectException e) {
    state.setText(" ");
    host.tell(
        e.kind() == StitchProjectException.Kind.SOURCES
            ? Messages.getString("StitchDialog.editRefused").formatted(bullets(e.problems()))
            : e.getMessage(),
        JOptionPane.ERROR_MESSAGE);
  }

  private void reopenFailed(Exception e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    // Its message can hold a path with the patient's name: for the user, not for the log.
    LOGGER.error("The saved stitch could not be reopened: {}", cause.getClass().getSimpleName());
    state.setText(" ");
    host.tell(
        Messages.getString("StitchDialog.editFailed").formatted(cause.getMessage()),
        JOptionPane.ERROR_MESSAGE);
  }

  private static String bullets(List<String> lines) {
    StringBuilder b = new StringBuilder();
    for (String line : lines) {
      b.append("<br>&bull; ").append(html(line));
    }
    return b.toString();
  }

  private void notOpened() {
    JOptionPane.showMessageDialog(
        this,
        Messages.getString("StitchDialog.savedNotOpened"),
        Messages.getString("StitchDialog.title"),
        JOptionPane.WARNING_MESSAGE);
  }

  /** The stitch stays in this dialog, so the user can free some space and press Save again. */
  private void saveFailed(Exception e) {
    Throwable cause = e.getCause() == null ? e : e.getCause();
    // Its message can hold a path with the patient's name: for the user, not for the log.
    LOGGER.error("The stitch could not be saved: {}", cause.getClass().getSimpleName());
    state.setText(" ");
    JOptionPane.showMessageDialog(
        this,
        Messages.getString("StitchDialog.saveFailed").formatted(cause.getMessage()),
        Messages.getString("StitchDialog.title"),
        JOptionPane.ERROR_MESSAGE);
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

  private static String reasonsHtml(Seam s) {
    StringBuilder b = new StringBuilder("<html><body style='width:420px'>");
    for (String r : s.reasons()) {
      b.append("&bull; ")
          .append(r.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
          .append("<br>");
    }
    return b.append("</body></html>").toString();
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
