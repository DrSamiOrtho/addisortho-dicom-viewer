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
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingWorker;

/**
 * Inspects one seam and lets the user correct it (design sections 4 and 5.4): a checkerboard or
 * difference of the two pieces over the overlap, nudges and turns from the keyboard, and matching
 * points picked in the two pieces.
 */
final class SeamEditor extends JDialog {

  private static final double GRID = MatchImage.FINE_MM;
  private static final double MARGIN_MM = 20;
  private static final double CHECK_MM = 10;
  private static final Color UPPER = new Color(0xA8FF30);
  private static final Color LOWER = new Color(0xFF8A30);

  private final Piece upper;
  private final Piece lower;
  private Seam seam;
  private Seam result;
  private final List<Rigid.Pair> pairs = new ArrayList<>();
  private double[] pendingUpper;

  private final ImagePanel aligned = new ImagePanel(null);
  private final ImagePanel upperView = new ImagePanel(this::clickUpper);
  private final ImagePanel lowerView = new ImagePanel(this::clickLower);
  private final JRadioButton checker =
      new JRadioButton(Messages.getString("SeamEditor.checker"), true);
  private final JToggleButton pick = new JToggleButton(Messages.getString("SeamEditor.pick"));
  private final JTextArea status = new JTextArea(4, 60);
  private final JLabel pairCount = new JLabel();

  // the regions shown: in the upper piece's mm {x0, y0, x1, y1}, and in the lower piece's mm
  private double[] upperBox;
  private double[] lowerBox;

  SeamEditor(Window owner, int index, Piece upper, Piece lower, Seam seam, SeamMatcher.Band band) {
    super(
        owner,
        Messages.getString("SeamEditor.title").formatted(index + 1),
        ModalityType.APPLICATION_MODAL);
    this.upper = upper;
    this.lower = lower;
    this.seam = seam;
    if (seam.method() == Seam.Method.MANUAL) {
      pairs.addAll(seam.pairs());
    }

    JTextArea help = text(Messages.getString("SeamEditor.help"), 2);
    JRadioButton diff = new JRadioButton(Messages.getString("SeamEditor.difference"));
    ButtonGroup mode = new ButtonGroup();
    mode.add(checker);
    mode.add(diff);
    checker.addActionListener(e -> renderAligned());
    diff.addActionListener(e -> renderAligned());

    JButton clear = new JButton(Messages.getString("SeamEditor.clear"));
    clear.addActionListener(
        e -> {
          pairs.clear();
          pendingUpper = null;
          renderPairs();
        });
    JButton fit = new JButton(Messages.getString("SeamEditor.fit"));
    fit.addActionListener(
        e -> {
          if (!pairs.isEmpty()) {
            setSeam(SeamMatcher.manual(pairs));
          }
        });
    JButton auto = new JButton(Messages.getString("SeamEditor.auto"));
    auto.addActionListener(
        e -> {
          auto.setEnabled(false);
          new SwingWorker<Seam, Void>() {
            @Override
            protected Seam doInBackground() {
              return SeamMatcher.match(upper.match(), lower.match(), band);
            }

            @Override
            protected void done() {
              auto.setEnabled(true);
              try {
                setSeam(get());
              } catch (Exception ex) {
                status.setText(String.valueOf(ex.getCause() == null ? ex : ex.getCause()));
              }
            }
          }.execute();
        });
    JButton apply = new JButton(Messages.getString("SeamEditor.apply"));
    apply.addActionListener(
        e -> {
          result = this.seam;
          dispose();
        });
    JButton cancel = new JButton(Messages.getString("StitchDialog.cancel"));
    cancel.addActionListener(e -> dispose());

    JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT));
    tools.add(checker);
    tools.add(diff);
    tools.add(pick);
    tools.add(clear);
    tools.add(fit);
    tools.add(auto);
    tools.add(pairCount);

    JPanel views = new JPanel(new GridLayout(1, 2, 6, 0));
    views.add(titled(upperView, Messages.getString("SeamEditor.upper")));
    views.add(titled(lowerView, Messages.getString("SeamEditor.lower")));
    aligned.setPreferredSize(new Dimension(720, 260));
    upperView.setPreferredSize(new Dimension(360, 220));
    lowerView.setPreferredSize(new Dimension(360, 220));

    JPanel centre = new JPanel(new BorderLayout(0, 6));
    centre.add(aligned, BorderLayout.CENTER);
    centre.add(views, BorderLayout.SOUTH);

    status.setEditable(false);
    status.setLineWrap(true);
    status.setWrapStyleWord(true);
    JPanel bottom = new JPanel(new BorderLayout());
    bottom.add(new JScrollPane(status), BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(cancel);
    buttons.add(apply);
    bottom.add(buttons, BorderLayout.SOUTH);

    JPanel north = new JPanel(new BorderLayout());
    north.add(help, BorderLayout.NORTH);
    north.add(tools, BorderLayout.SOUTH);

    JPanel root = new JPanel(new BorderLayout(0, 6));
    root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
    root.add(north, BorderLayout.NORTH);
    root.add(centre, BorderLayout.CENTER);
    root.add(bottom, BorderLayout.SOUTH);
    setContentPane(root);
    bindKeys(root);
    setSeam(seam);
    pack();
    setLocationRelativeTo(owner);
  }

  /** The seam the user chose, empty when cancelled. */
  Optional<Seam> result() {
    return Optional.ofNullable(result);
  }

  private void setSeam(Seam s) {
    seam = s;
    double[] box =
        SeamMatcher.overlap(upper.widthMm(), upper.heightMm(), s.transform(), lower.match());
    double top = Math.max(0, box[1] - MARGIN_MM);
    upperBox = new double[] {0, top, upper.widthMm(), upper.heightMm()};
    double[] a = s.transform().inverse().apply(0, box[1]);
    double[] b = s.transform().inverse().apply(0, box[3]);
    double lowBottom = Math.min(lower.heightMm(), Math.max(a[1], b[1]) + MARGIN_MM);
    lowerBox = new double[] {0, 0, lower.widthMm(), Math.max(lowBottom, MARGIN_MM)};
    status.setText(s.summary() + "\n" + String.join("\n", s.reasons()));
    status.setCaretPosition(0);
    renderAligned();
    renderPairs();
  }

  private void renderAligned() {
    Gray u = upper.match().fine();
    Gray l = lower.match().fine();
    Rigid toLower = seam.transform().inverse();
    int w = (int) Math.ceil((upperBox[2] - upperBox[0]) / GRID);
    int h =
        (int)
            Math.ceil(
                (Math.min(upperBox[3] + MARGIN_MM, seam.transform().apply(0, lowerBox[3])[1])
                        - upperBox[1])
                    / GRID);
    h = Math.max(1, h);
    float[] v = new float[w * h];
    boolean check = checker.isSelected();
    int cell = (int) Math.round(CHECK_MM / GRID);
    for (int y = 0; y < h; y++) {
      double py = upperBox[1] + (y + 0.5) * GRID;
      for (int x = 0; x < w; x++) {
        double px = upperBox[0] + (x + 0.5) * GRID;
        float a = u.sample(px, py);
        double[] q = toLower.apply(px, py);
        float b = l.sample(q[0], q[1]);
        if (check) {
          boolean useUpper = ((x / cell + y / cell) & 1) == 0;
          float pickV = useUpper ? a : b;
          v[y * w + x] = Float.isNaN(pickV) ? (useUpper ? b : a) : pickV;
        } else {
          v[y * w + x] =
              Float.isNaN(a) || Float.isNaN(b) ? Float.NaN : Math.min(1f, 3 * Math.abs(a - b));
        }
      }
    }
    aligned.setImage(ImagePanel.gray(v, w, h), null);
  }

  private void renderPairs() {
    upperView.setImage(crop(upper.match().fine(), upperBox), (g, s) -> drawPoints(g, s, true));
    lowerView.setImage(crop(lower.match().fine(), lowerBox), (g, s) -> drawPoints(g, s, false));
    pairCount.setText(Messages.getString("SeamEditor.pairs").formatted(pairs.size()));
  }

  private static java.awt.image.BufferedImage crop(Gray g, double[] box) {
    int w = Math.max(1, (int) Math.ceil((box[2] - box[0]) / GRID));
    int h = Math.max(1, (int) Math.ceil((box[3] - box[1]) / GRID));
    float[] v = new float[w * h];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        v[y * w + x] = g.sample(box[0] + (x + 0.5) * GRID, box[1] + (y + 0.5) * GRID);
      }
    }
    return ImagePanel.gray(v, w, h);
  }

  private void drawPoints(Graphics2D g, double scale, boolean inUpper) {
    double[] box = inUpper ? upperBox : lowerBox;
    g.setStroke(new BasicStroke(2f));
    int n = 1;
    for (Rigid.Pair p : pairs) {
      double x = inUpper ? p.tx() : p.fx();
      double y = inUpper ? p.ty() : p.fy();
      mark(
          g,
          (x - box[0]) / GRID * scale,
          (y - box[1]) / GRID * scale,
          inUpper ? UPPER : LOWER,
          n++);
    }
    if (inUpper && pendingUpper != null) {
      mark(
          g,
          (pendingUpper[0] - box[0]) / GRID * scale,
          (pendingUpper[1] - box[1]) / GRID * scale,
          Color.WHITE,
          n);
    }
  }

  private static void mark(Graphics2D g, double x, double y, Color c, int n) {
    g.setColor(c);
    g.draw(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
    g.drawString(Integer.toString(n), (float) x + 8, (float) y - 8);
  }

  private void clickUpper(double px, double py) {
    if (pick.isSelected()) {
      pendingUpper = new double[] {upperBox[0] + px * GRID, upperBox[1] + py * GRID};
      renderPairs();
    }
  }

  private void clickLower(double px, double py) {
    if (pick.isSelected() && pendingUpper != null) {
      pairs.add(
          new Rigid.Pair(
              lowerBox[0] + px * GRID, lowerBox[1] + py * GRID, pendingUpper[0], pendingUpper[1]));
      pendingUpper = null;
      renderPairs();
    }
  }

  // moves the lower piece in the upper piece's frame; turns about the overlap's centre
  private void move(double dx, double dy, double degrees) {
    double[] box =
        SeamMatcher.overlap(upper.widthMm(), upper.heightMm(), seam.transform(), lower.match());
    double cx = (box[0] + box[2]) / 2;
    double cy = (box[1] + box[3]) / 2;
    Rigid turn =
        Rigid.shift(cx, cy)
            .after(new Rigid(Math.toRadians(degrees), 0, 0))
            .after(Rigid.shift(-cx, -cy));
    setSeam(seam.adjusted(Rigid.shift(dx, dy).after(turn).after(seam.transform())));
  }

  private void bindKeys(JComponent root) {
    key(root, KeyEvent.VK_LEFT, 0, "left", () -> move(-0.1, 0, 0));
    key(root, KeyEvent.VK_RIGHT, 0, "right", () -> move(0.1, 0, 0));
    key(root, KeyEvent.VK_UP, 0, "up", () -> move(0, -0.1, 0));
    key(root, KeyEvent.VK_DOWN, 0, "down", () -> move(0, 0.1, 0));
    key(root, KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK, "left1", () -> move(-1, 0, 0));
    key(root, KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK, "right1", () -> move(1, 0, 0));
    key(root, KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK, "up1", () -> move(0, -1, 0));
    key(root, KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK, "down1", () -> move(0, 1, 0));
    key(root, KeyEvent.VK_OPEN_BRACKET, 0, "turnLeft", () -> move(0, 0, -0.1));
    key(root, KeyEvent.VK_CLOSE_BRACKET, 0, "turnRight", () -> move(0, 0, 0.1));
  }

  private static void key(JComponent c, int code, int mods, String name, Runnable r) {
    c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(code, mods), name);
    c.getActionMap()
        .put(
            name,
            new AbstractAction() {
              @Override
              public void actionPerformed(ActionEvent e) {
                r.run();
              }
            });
  }

  private static JPanel titled(JComponent c, String title) {
    JPanel p = new JPanel(new BorderLayout());
    p.setBorder(BorderFactory.createTitledBorder(title));
    p.add(c);
    return p;
  }

  static JTextArea text(String s, int rows) {
    JTextArea t = new JTextArea(s, rows, 60);
    t.setEditable(false);
    t.setLineWrap(true);
    t.setWrapStyleWord(true);
    t.setOpaque(false);
    t.setBorder(null);
    return t;
  }
}
