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
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.vr.View3d;

/** Scalpel tool: a drag draws the closed area the cut inside / cut outside commands then use. */
public class LassoMouseListener extends MouseToolListener {

  public LassoMouseListener() {
    super(ActionVol.SCALPEL);
  }

  @Override
  public void mousePressed(MouseEvent e) {
    View3d view = targetOf(e);
    if (view != null) {
      view.lassoStart(e.getPoint());
    }
  }

  @Override
  public void mouseDragged(MouseEvent e) {
    View3d view = targetOf(e);
    if (view != null) {
      view.lassoExtend(e.getPoint());
    }
  }

  @Override
  public void mouseReleased(MouseEvent e) {
    View3d view = releaseTargetOf(e);
    if (view != null) {
      view.lassoClose();
    }
  }
}
