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
package org.weasis.dicom.viewer3d;

import java.awt.event.ActionEvent;
import java.util.List;
import org.weasis.core.api.gui.util.Feature;
import org.weasis.core.api.service.WProperties;
import org.weasis.core.ui.editor.image.ImageViewerEventManager;
import org.weasis.core.ui.editor.image.ViewerToolBar;
import org.weasis.dicom.codec.DicomImageElement;

/**
 * The common viewer tool bar of the 3D viewer. It lists the 3D tools among the mouse actions (the
 * 2D viewer's popups leave them out, as they are not enabled there) and tells the {@link
 * EventManager} when a mouse button's tool changes from its popups.
 */
public class View3dViewerToolBar extends ViewerToolBar<DicomImageElement> {

  private static final List<Feature<?>> TOOLS_3D =
      List.of(
          ActionVol.ROLL,
          ActionVol.SCALPEL,
          ActionVol.KEEP_THIS,
          ActionVol.REMOVE_THIS,
          ActionVol.LENGTH,
          ActionVol.ARROW);

  static {
    synchronized (actionsButtons) {
      for (Feature<?> tool : TOOLS_3D) {
        if (!actionsButtons.contains(tool)) {
          // Before "no action", which stays last.
          int last = Math.max(0, actionsButtons.size() - 1);
          actionsButtons.add(last, tool);
        }
      }
    }
  }

  public View3dViewerToolBar(
      ImageViewerEventManager<DicomImageElement> eventManager,
      int activeMouse,
      WProperties props,
      int index) {
    super(eventManager, activeMouse, props, index, false);
  }

  @Override
  public void actionPerformed(ActionEvent e) {
    super.actionPerformed(e);
    if (eventManager instanceof EventManager manager) {
      manager.fireMouseToolChanged();
    }
  }
}
