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
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The free-hand area the scalpel draws over the view, in view pixels: open while the mouse is
 * dragged, closed on release and kept until it is cut or cleared.
 */
public final class Lasso {

  /** Points closer than this to the last one are not added, in pixels. */
  private static final double MIN_STEP = 1.5;

  private final List<Point2D> points = new ArrayList<>();
  private boolean closed;

  public void start(Point2D p) {
    points.clear();
    closed = false;
    points.add(new Point2D.Double(p.getX(), p.getY()));
  }

  public void extend(Point2D p) {
    if (closed || points.isEmpty()) {
      return;
    }
    if (points.getLast().distance(p) >= MIN_STEP) {
      points.add(new Point2D.Double(p.getX(), p.getY()));
    }
  }

  /** Closes the area; an area of fewer than three points is dropped. */
  public void close() {
    if (points.size() < 3) {
      clear();
      return;
    }
    closed = true;
  }

  public void clear() {
    points.clear();
    closed = false;
  }

  public boolean isEmpty() {
    return points.isEmpty();
  }

  public boolean isClosed() {
    return closed && points.size() >= 3;
  }

  public List<Point2D> points() {
    return Collections.unmodifiableList(points);
  }

  /** Draws the area: a dashed outline while open, a solid one with a light fill once closed. */
  public void draw(Graphics2D g2d) {
    if (points.size() < 2) {
      return;
    }
    Path2D.Double path = new Path2D.Double();
    for (int i = 0; i < points.size(); i++) {
      Point2D p = points.get(i);
      if (i == 0) {
        path.moveTo(p.getX(), p.getY());
      } else {
        path.lineTo(p.getX(), p.getY());
      }
    }
    Stroke oldStroke = g2d.getStroke();
    Color oldColor = g2d.getColor();
    if (closed) {
      path.closePath();
      g2d.setColor(new Color(255, 220, 0, 40));
      g2d.fill(path);
      g2d.setStroke(new BasicStroke(1.5f));
    } else {
      g2d.setStroke(
          new BasicStroke(
              1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6f, 4f}, 0f));
    }
    g2d.setColor(Color.BLACK);
    g2d.translate(1, 1);
    g2d.draw(path);
    g2d.translate(-1, -1);
    g2d.setColor(new Color(255, 220, 0));
    g2d.draw(path);
    g2d.setStroke(oldStroke);
    g2d.setColor(oldColor);
  }
}
