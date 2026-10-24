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

import java.awt.event.KeyEvent;
import java.util.Optional;
import org.weasis.dicom.viewer3d.geometry.CameraView;

/**
 * The six standard orientations of the 3D view and their keys, as in RadiAnt's 3D volume rendering
 * window: 1 anterior, 2 posterior, 3 left, 4 right, 5 superior, 6 inferior (the digit keys of the
 * main keyboard or of the numeric keypad).
 */
public enum StandardView {
  ANTERIOR(1, "A", "view.anterior", CameraView.FRONT), // NON-NLS
  POSTERIOR(2, "P", "view.posterior", CameraView.BACK), // NON-NLS
  LEFT(3, "L", "view.left", CameraView.LEFT), // NON-NLS
  RIGHT(4, "R", "view.right", CameraView.RIGHT), // NON-NLS
  SUPERIOR(5, "S", "view.superior", CameraView.TOP), // NON-NLS
  INFERIOR(6, "I", "view.inferior", CameraView.BOTTOM); // NON-NLS

  private final int digit;
  private final String letter;
  private final String messageKey;
  private final CameraView cameraView;

  StandardView(int digit, String letter, String messageKey, CameraView cameraView) {
    this.digit = digit;
    this.letter = letter;
    this.messageKey = messageKey;
    this.cameraView = cameraView;
  }

  /** The key, 1 to 6. */
  public int digit() {
    return digit;
  }

  /** The letter on the tool bar button. */
  public String letter() {
    return letter;
  }

  public String messageKey() {
    return messageKey;
  }

  public CameraView cameraView() {
    return cameraView;
  }

  /** The orientation of a pressed key, digit row or numeric keypad. */
  public static Optional<StandardView> ofKey(int keyCode) {
    int digit;
    if (keyCode >= KeyEvent.VK_1 && keyCode <= KeyEvent.VK_6) {
      digit = keyCode - KeyEvent.VK_0;
    } else if (keyCode >= KeyEvent.VK_NUMPAD1 && keyCode <= KeyEvent.VK_NUMPAD6) {
      digit = keyCode - KeyEvent.VK_NUMPAD0;
    } else {
      return Optional.empty();
    }
    return ofDigit(digit);
  }

  public static Optional<StandardView> ofDigit(int digit) {
    for (StandardView v : values()) {
      if (v.digit == digit) {
        return Optional.of(v);
      }
    }
    return Optional.empty();
  }
}
