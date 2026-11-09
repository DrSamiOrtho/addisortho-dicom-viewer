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

import java.awt.Font;
import java.awt.font.FontRenderContext;
import org.junit.jupiter.api.Test;

class AoFontsTest {
  private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

  private static double width(Font font, String text) {
    return font.getStringBounds(text, FRC).getWidth();
  }

  @Test
  void theBundledFontLoadsAndIsNotAFallback() {
    assertTrue(AoFonts.isBundled());
    assertEquals("AO UI", AoFonts.regular(14).getFamily());
  }

  @Test
  void latinAndAmharicNamesCanBothBeDrawn() {
    for (Font font : new Font[] {AoFonts.regular(14), AoFonts.semibold(14)}) {
      assertEquals(-1, font.canDisplayUpTo("Synthetic Patient ሙከራ ታካሚ አንድ 0123456789"));
    }
  }

  @Test
  void allDigitsAreOneWidthSoRecordNumbersLineUp() {
    Font font = AoFonts.regular(14);
    double zero = width(font, "0");
    for (char digit = '1'; digit <= '9'; digit++) {
      assertEquals(zero, width(font, String.valueOf(digit)), 0.001);
    }
  }
}
