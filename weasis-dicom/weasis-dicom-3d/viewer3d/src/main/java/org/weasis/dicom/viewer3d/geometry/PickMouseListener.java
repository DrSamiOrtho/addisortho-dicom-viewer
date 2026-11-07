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
package org.weasis.dicom.viewer3d.geometry;

import java.awt.Point;
import java.awt.event.MouseEvent;
import org.weasis.core.api.gui.util.ActionState;
import org.weasis.core.api.gui.util.Feature;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * The tools that act on the point of the volume under the mouse: keep only this, remove this and
 * the length act on a click; the arrow is dragged from its tail to its head.
 */
public class PickMouseListener extends MouseToolListener {

  /** What a pick does. */
  public enum Mode {
    KEEP,
    REMOVE,
    LENGTH,
    ARROW
  }

  /** A press and release further apart than this is a drag, not a click. */
  private static final int CLICK_TOLERANCE = 4;

  private final Mode mode;
  private Point pressed;

  public PickMouseListener(Feature<? extends ActionState> action, Mode mode) {
    super(action);
    this.mode = mode;
  }

  public Mode getMode() {
    return mode;
  }

  @Override
  public void mousePressed(MouseEvent e) {
    View3d view = targetOf(e);
    if (view == null) {
      return;
    }
    pressed = e.getPoint();
    if (mode == Mode.ARROW) {
      view.arrowStart(e.getPoint());
    }
  }

  @Override
  public void mouseDragged(MouseEvent e) {
    View3d view = targetOf(e);
    if (view != null && mode == Mode.ARROW) {
      view.arrowDrag(e.getPoint());
    }
  }

  @Override
  public void mouseReleased(MouseEvent e) {
    View3d view = releaseTargetOf(e);
    Point start = pressed;
    pressed = null;
    if (view == null) {
      return;
    }
    switch (mode) {
      case ARROW -> view.arrowEnd(e.getPoint());
      case LENGTH -> {
        if (isClick(start, e.getPoint())) {
          view.lengthClick(e.getPoint());
        }
      }
      case KEEP -> {
        if (isClick(start, e.getPoint())) {
          view.keepOnlyAt(e.getPoint());
        }
      }
      case REMOVE -> {
        if (isClick(start, e.getPoint())) {
          view.removeAt(e.getPoint());
        }
      }
    }
  }

  private static boolean isClick(Point start, Point end) {
    return start != null && start.distance(end) <= CLICK_TOLERANCE;
  }
}
