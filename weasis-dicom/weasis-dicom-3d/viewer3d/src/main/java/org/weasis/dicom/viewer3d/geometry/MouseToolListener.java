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

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import org.weasis.core.api.gui.util.ActionState;
import org.weasis.core.api.gui.util.BasicActionState;
import org.weasis.core.api.gui.util.Feature;
import org.weasis.core.api.gui.util.MouseActionAdapter;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * A mouse tool of the 3D view that is also an action, so the mouse-action framework can put it on a
 * mouse button: the roll, the scalpel, keep/remove, the length and the arrow.
 */
public abstract class MouseToolListener extends MouseActionAdapter implements ActionState {

  private final BasicActionState basicState;

  protected MouseToolListener(Feature<? extends ActionState> action) {
    this.basicState = new BasicActionState(action);
  }

  @Override
  public void enableAction(boolean enabled) {
    basicState.enableAction(enabled);
  }

  @Override
  public boolean isActionEnabled() {
    return basicState.isActionEnabled();
  }

  @Override
  public boolean registerActionState(Object c) {
    return basicState.registerActionState(c);
  }

  @Override
  public void unregisterActionState(Object c) {
    basicState.unregisterActionState(c);
  }

  @Override
  public Feature<? extends ActionState> getActionW() {
    return basicState.getActionW();
  }

  @Override
  public String toString() {
    return basicState.getActionW().getTitle();
  }

  /** The 3D view the event comes from when the tool is enabled and the button is its own. */
  protected View3d targetOf(MouseEvent e) {
    if (!basicState.isActionEnabled() || e.isConsumed()) {
      return null;
    }
    if ((e.getModifiersEx() & getButtonMaskEx()) == 0) {
      return null;
    }
    return e.getSource() instanceof View3d view3d ? view3d : null;
  }

  /** As {@link #targetOf} for a release, whose modifiers no longer carry the button. */
  protected View3d releaseTargetOf(MouseEvent e) {
    if (!basicState.isActionEnabled() || e.isConsumed()) {
      return null;
    }
    if ((e.getModifiers() & getButtonMask()) == 0) {
      return null;
    }
    return e.getSource() instanceof View3d view3d ? view3d : null;
  }

  /** Whether the Alt key is down, which hands a left drag to the scalpel whatever the tool. */
  public static boolean isAltDown(InputEvent e) {
    return (e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0;
  }
}
