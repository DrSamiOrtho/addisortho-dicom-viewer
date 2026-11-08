/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import org.junit.jupiter.api.Test;

class AoDesignTest {
  private static final double READABLE = 4.5;

  @Test
  void theBrandPairsAreReadable() {
    assertTrue(AoDesign.contrast(AoDesign.GREEN, Color.WHITE) >= READABLE);
    assertTrue(AoDesign.contrast(AoDesign.LIME, Color.BLACK) >= READABLE);
    assertTrue(AoDesign.contrast(AoDesign.LIME, AoDesign.GREEN) >= READABLE);
  }

  @Test
  void limeOnWhiteIsNotReadableWhichIsWhyItIsNeverUsed() {
    assertTrue(AoDesign.contrast(AoDesign.LIME, Color.WHITE) < READABLE);
  }

  @Test
  void blackOnWhiteIsTheMaximum() {
    assertEquals(21.0, AoDesign.contrast(Color.BLACK, Color.WHITE), 0.01);
  }
}
