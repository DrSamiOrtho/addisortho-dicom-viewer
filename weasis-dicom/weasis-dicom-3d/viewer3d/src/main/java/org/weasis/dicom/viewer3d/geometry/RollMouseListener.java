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

import java.awt.event.MouseEvent;
import org.joml.Quaterniond;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Roll tool: a drag turns the volume about the view axis, by the angle the mouse sweeps around the
 * centre of the view, as a dial would.
 */
public class RollMouseListener extends MouseToolListener {

  private double lastAngle;

  public RollMouseListener() {
    super(ActionVol.ROLL);
  }

  private static double angleAt(View3d view, MouseEvent e) {
    double cx = view.getWidth() / 2.0;
    double cy = view.getHeight() / 2.0;
    return Math.atan2(cy - e.getY(), e.getX() - cx);
  }

  @Override
  public void mousePressed(MouseEvent e) {
    View3d view = targetOf(e);
    if (view != null) {
      view.getCamera().setAdjusting(true);
      lastAngle = angleAt(view, e);
    }
  }

  @Override
  public void mouseDragged(MouseEvent e) {
    View3d view = targetOf(e);
    if (view != null) {
      double angle = angleAt(view, e);
      double delta = angle - lastAngle;
      if (delta > Math.PI) {
        delta -= 2 * Math.PI;
      } else if (delta < -Math.PI) {
        delta += 2 * Math.PI;
      }
      lastAngle = angle;
      view.getCamera().setAdjusting(true);
      view.getCamera().roll(delta);
      EventManager.getInstance()
          .fireRotationSync(view, new Quaterniond(view.getCamera().getRotation()), true);
    }
  }

  @Override
  public void mouseReleased(MouseEvent e) {
    View3d view = releaseTargetOf(e);
    if (view != null) {
      view.getCamera().setAdjusting(false);
      EventManager.getInstance()
          .fireRotationSync(view, new Quaterniond(view.getCamera().getRotation()), false);
      view.repaint();
    }
  }
}
