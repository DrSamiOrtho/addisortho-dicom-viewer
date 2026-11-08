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
package org.weasis.dicom.viewer3d.vr;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.joml.Vector3d;
import org.weasis.core.api.util.FontTools;

/**
 * Lengths and arrows anchored on the volume: their end points are kept in texture coordinates, so
 * they follow the volume through every rotation, pan and zoom and are drawn over the rendering at
 * their projected positions.
 */
public final class Annotations3d {

  /** What an annotation shows. */
  public enum Kind {
    LENGTH,
    ARROW
  }

  /**
   * @param a first end, texture coordinates
   * @param b second end, texture coordinates
   * @param label text drawn at the middle, empty for none
   */
  public record Item(Kind kind, Vector3d a, Vector3d b, String label) {}

  private static final Color LINE = new Color(90, 220, 255);
  private static final Color SHADOW = Color.BLACK;
  private static final double ARROW_HEAD = 12.0;

  private final List<Item> items = new ArrayList<>();

  public void add(Item item) {
    items.add(item);
  }

  public boolean removeLast() {
    if (items.isEmpty()) {
      return false;
    }
    items.removeLast();
    return true;
  }

  public void clear() {
    items.clear();
  }

  public boolean isEmpty() {
    return items.isEmpty();
  }

  public List<Item> items() {
    return Collections.unmodifiableList(items);
  }

  /** Length in millimetres between two volume voxel positions, given the voxel spacing. */
  public static double lengthMm(Vector3d voxelA, Vector3d voxelB, Vector3d spacingMm) {
    double dx = (voxelB.x - voxelA.x) * spacingMm.x;
    double dy = (voxelB.y - voxelA.y) * spacingMm.y;
    double dz = (voxelB.z - voxelA.z) * spacingMm.z;
    return Math.sqrt(dx * dx + dy * dy + dz * dz);
  }

  /**
   * Draws every annotation.
   *
   * @param project texture coordinate to view pixel, {@code null} when behind the camera
   */
  public void draw(Graphics2D g2d, Function<Vector3d, Point2D> project, Font font) {
    if (items.isEmpty()) {
      return;
    }
    Stroke oldStroke = g2d.getStroke();
    Color oldColor = g2d.getColor();
    Font oldFont = g2d.getFont();
    g2d.setFont(font);
    for (Item item : items) {
      Point2D a = project.apply(item.a());
      Point2D b = project.apply(item.b());
      if (a == null || b == null) {
        continue;
      }
      drawItem(g2d, item, a, b);
    }
    g2d.setStroke(oldStroke);
    g2d.setColor(oldColor);
    g2d.setFont(oldFont);
  }

  /** Draws one segment as a length or an arrow between two view points. */
  public static void drawItem(Graphics2D g2d, Item item, Point2D a, Point2D b) {
    Line2D line = new Line2D.Double(a, b);
    Path2D shape = new Path2D.Double(line);
    if (item.kind() == Kind.ARROW) {
      shape.append(arrowHead(a, b), false);
    }
    g2d.setStroke(new BasicStroke(3f));
    g2d.setColor(SHADOW);
    g2d.draw(shape);
    g2d.setStroke(new BasicStroke(1.5f));
    g2d.setColor(LINE);
    g2d.draw(shape);
    if (item.kind() == Kind.LENGTH) {
      drawEndMark(g2d, a);
      drawEndMark(g2d, b);
    }
    if (!item.label().isEmpty()) {
      FontMetrics fm = g2d.getFontMetrics();
      float x = (float) ((a.getX() + b.getX()) / 2 + 6);
      float y = (float) ((a.getY() + b.getY()) / 2 - fm.getDescent());
      FontTools.paintColorFontOutline(g2d, item.label(), x, y, LINE);
    }
  }

  /** Draws the first point of a length while the second one is being picked. */
  public static void drawEndMark(Graphics2D g2d, Point2D p) {
    Ellipse2D dot = new Ellipse2D.Double(p.getX() - 3, p.getY() - 3, 6, 6);
    g2d.setColor(SHADOW);
    g2d.setStroke(new BasicStroke(3f));
    g2d.draw(dot);
    g2d.setColor(LINE);
    g2d.setStroke(new BasicStroke(1.5f));
    g2d.draw(dot);
  }

  private static Path2D arrowHead(Point2D from, Point2D to) {
    double dx = to.getX() - from.getX();
    double dy = to.getY() - from.getY();
    double len = Math.hypot(dx, dy);
    Path2D.Double head = new Path2D.Double();
    if (len < 1e-6) {
      return head;
    }
    double ux = dx / len;
    double uy = dy / len;
    double px = -uy;
    double py = ux;
    double bx = to.getX() - ux * ARROW_HEAD;
    double by = to.getY() - uy * ARROW_HEAD;
    head.moveTo(to.getX(), to.getY());
    head.lineTo(bx + px * ARROW_HEAD * 0.4, by + py * ARROW_HEAD * 0.4);
    head.moveTo(to.getX(), to.getY());
    head.lineTo(bx - px * ARROW_HEAD * 0.4, by - py * ARROW_HEAD * 0.4);
    return head;
  }
}
