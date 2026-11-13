/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.dicom.explorer.Messages;

/**
 * Shows the pictures found and asks whose they are. Nothing is ticked for the user unless there is
 * only the one picture, because sharing a folder does not make two pictures the same patient's:
 * several ticked pictures need an explicit confirmation that they are.
 */
final class PictureConfirmDialog {
  private static final int FIELD_COLUMNS = 28;
  private static final int THUMBNAIL = 96;
  private static final int COLUMNS = 4;
  private static final int LIST_WIDTH = 520;
  private static final int LIST_HEIGHT = 190;

  /** Pictures a person said belong to one patient. */
  record Assignment(List<Path> pictures, PictureIdentity identity) {}

  private PictureConfirmDialog() {}

  /**
   * Asks until every picture is dealt with or the user is done, so that a mixed folder can be
   * sorted patient by patient.
   *
   * @param tickFirst start with the pictures ticked: only for one picture and nothing else
   * @return what to keep; empty when the user wants none of them kept
   */
  static List<Assignment> review(Component parent, List<Path> pictures, boolean tickFirst) {
    List<Assignment> kept = new ArrayList<>();
    List<Path> left = new ArrayList<>(pictures);
    boolean tick = tickFirst;
    while (!left.isEmpty()) {
      Optional<Assignment> one = ask(parent, left, tick, !kept.isEmpty());
      if (one.isEmpty()) {
        break;
      }
      kept.add(one.get());
      left.removeAll(one.get().pictures());
      tick = false;
    }
    return kept;
  }

  /**
   * @return the key of the message to show, or null when the selection can be kept
   */
  static String selectionProblem(int ticked, boolean sameConfirmed) {
    if (ticked == 0) {
      return "library.picture.none";
    }
    return ticked > 1 && !sameConfirmed ? "library.picture.same.required" : null;
  }

  private static Optional<Assignment> ask(
      Component parent, List<Path> pictures, boolean tick, boolean someKept) {
    Map<Path, JCheckBox> boxes = new LinkedHashMap<>();
    Map<Path, JLabel> previews = new LinkedHashMap<>();
    JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 8, 8));
    for (Path picture : pictures) {
      JCheckBox box = new JCheckBox(picture.getFileName().toString(), tick);
      JLabel preview = new JLabel(Messages.getString("library.picture.no.preview"));
      preview.setHorizontalAlignment(SwingConstants.CENTER);
      preview.setPreferredSize(
          new Dimension(GuiUtils.getScaleLength(THUMBNAIL), GuiUtils.getScaleLength(THUMBNAIL)));
      preview.addMouseListener(
          new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
              box.doClick();
            }
          });
      JPanel cell = new JPanel(new BorderLayout());
      cell.add(preview, BorderLayout.CENTER);
      cell.add(box, BorderLayout.SOUTH);
      grid.add(cell);
      boxes.put(picture, box);
      previews.put(picture, preview);
    }
    JScrollPane list = new JScrollPane(grid);
    list.setPreferredSize(
        new Dimension(GuiUtils.getScaleLength(LIST_WIDTH), GuiUtils.getScaleLength(LIST_HEIGHT)));
    JButton all = new JButton(Messages.getString("library.picture.select.all"));
    all.addActionListener(_ -> boxes.values().forEach(b -> b.setSelected(true)));
    JButton none = new JButton(Messages.getString("library.picture.select.none"));
    none.addActionListener(_ -> boxes.values().forEach(b -> b.setSelected(false)));

    JTextField name = new JTextField(FIELD_COLUMNS);
    JTextField id = new JTextField(FIELD_COLUMNS);
    JTextField date = new JTextField(FIELD_COLUMNS);
    JCheckBox same = new JCheckBox(Messages.getString("library.picture.same"));
    JPanel fields = new JPanel(new GridLayout(0, 1, 0, GuiUtils.getScaleLength(4)));
    fields.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
    fields.add(labelled("library.picture.name", name));
    fields.add(name);
    fields.add(labelled("library.picture.id", id));
    fields.add(id);
    fields.add(labelled("library.picture.date", date));
    fields.add(date);
    fields.add(same);

    String intro =
        MessageFormat.format(Messages.getString("library.picture.intro"), pictures.size());
    JPanel selection = new JPanel();
    selection.add(all);
    selection.add(none);
    Object[] content = {intro, list, selection, fields, Messages.getString("library.picture.note")};
    String keep = Messages.getString("library.picture.keep");
    String no = Messages.getString(someKept ? "library.picture.more" : "library.picture.decline");
    String title = Messages.getString("library.picture.title");

    AtomicBoolean closed = new AtomicBoolean();
    loadPreviews(previews, closed);
    try {
      while (true) {
        int choice =
            JOptionPane.showOptionDialog(
                parent,
                content,
                title,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new Object[] {keep, no},
                no);
        if (choice != 0) {
          return Optional.empty();
        }
        List<Path> ticked =
            boxes.entrySet().stream()
                .filter(e -> e.getValue().isSelected())
                .map(Map.Entry::getKey)
                .toList();
        String problem = selectionProblem(ticked.size(), same.isSelected());
        if (problem != null) {
          warn(parent, title, problem);
          continue;
        }
        try {
          PictureIdentity identity =
              PictureIdentity.parse(name.getText(), id.getText(), date.getText(), LocalDate.now());
          if (!identity.isAnonymous() || confirmedAnonymous(parent, title)) {
            return Optional.of(new Assignment(ticked, identity));
          }
        } catch (PictureIdentity.Invalid e) {
          warn(parent, title, e.getMessage());
        }
      }
    } finally {
      closed.set(true);
    }
  }

  private static void warn(Component parent, String title, String key) {
    JOptionPane.showMessageDialog(
        parent, Messages.getString(key), title, JOptionPane.WARNING_MESSAGE);
  }

  private static void loadPreviews(Map<Path, JLabel> previews, AtomicBoolean closed) {
    int size = GuiUtils.getScaleLength(THUMBNAIL);
    new SwingWorker<Void, Object[]>() {
      @Override
      protected Void doInBackground() {
        for (Map.Entry<Path, JLabel> e : previews.entrySet()) {
          if (closed.get()) {
            break;
          }
          BufferedImage image = thumbnail(e.getKey(), size);
          if (image != null) {
            publish(new Object[] {e.getValue(), image});
          }
        }
        return null;
      }

      @Override
      protected void process(List<Object[]> ready) {
        for (Object[] item : ready) {
          JLabel label = (JLabel) item[0];
          label.setText(null);
          label.setIcon(new ImageIcon((BufferedImage) item[1]));
        }
      }
    }.execute();
  }

  /**
   * @return a small copy, read at reduced size so a very large photograph costs little, or null
   *     when the picture cannot be shown
   */
  static BufferedImage thumbnail(Path file, int size) {
    try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
      Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
      if (readers == null || !readers.hasNext()) {
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(in, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        int step = Math.max(1, Math.max(width, height) / (size * 2));
        ImageReadParam param = reader.getDefaultReadParam();
        param.setSourceSubsampling(step, step, 0, 0);
        return fit(reader.read(0, param), size);
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException | OutOfMemoryError e) {
      return null;
    }
  }

  private static BufferedImage fit(BufferedImage image, int size) {
    double scale = Math.min(1.0, size / (double) Math.max(image.getWidth(), image.getHeight()));
    int width = Math.max(1, (int) (image.getWidth() * scale));
    int height = Math.max(1, (int) (image.getHeight() * scale));
    BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = result.createGraphics();
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    g.drawImage(image, 0, 0, width, height, null);
    g.dispose();
    return result;
  }

  private static JLabel labelled(String key, JTextField field) {
    JLabel label = new JLabel(Messages.getString(key));
    label.setLabelFor(field);
    return label;
  }

  private static boolean confirmedAnonymous(Component parent, String title) {
    return JOptionPane.showConfirmDialog(
            parent,
            Messages.getString("library.picture.anonymous"),
            title,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE)
        == JOptionPane.YES_OPTION;
  }
}
