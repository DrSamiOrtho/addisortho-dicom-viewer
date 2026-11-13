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

import com.formdev.flatlaf.util.UIScale;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bundled interface font: Noto Sans and Noto Sans Ethiopic merged into one file per weight (SIL
 * Open Font License, see ao/fonts/OFL.txt), so that an Amharic name matches a Latin one. Java does
 * not fall back reliably from one bundled font to another, hence the merge.
 */
public final class AoFonts {
  private static final Logger LOGGER = LoggerFactory.getLogger(AoFonts.class);
  private static final Font REGULAR = load("ao/fonts/AoUi-Regular.ttf"); // NON-NLS
  private static final Font SEMIBOLD = load("ao/fonts/AoUi-SemiBold.ttf"); // NON-NLS

  private AoFonts() {}

  private static Font load(String resource) {
    try (InputStream in = AoFonts.class.getClassLoader().getResourceAsStream(resource)) {
      if (in != null) {
        return Font.createFont(Font.TRUETYPE_FONT, in);
      }
      LOGGER.error("The interface font is missing from the application");
    } catch (IOException | FontFormatException | RuntimeException e) {
      LOGGER.error("Cannot load the interface font: {}", e.getClass().getSimpleName());
    }
    return null;
  }

  /** Whether the bundled font really loaded, rather than the system's being used instead. */
  public static boolean isBundled() {
    return REGULAR != null && SEMIBOLD != null;
  }

  /**
   * @param size in points at 100% scale
   */
  public static Font regular(float size) {
    return derive(REGULAR, Font.PLAIN, size);
  }

  /**
   * @param size in points at 100% scale
   */
  public static Font semibold(float size) {
    return derive(SEMIBOLD, Font.BOLD, size);
  }

  private static Font derive(Font font, int fallbackStyle, float size) {
    float scaled = UIScale.scale(size);
    return font == null
        ? new Font(Font.SANS_SERIF, fallbackStyle, Math.round(scaled))
        : font.deriveFont(scaled);
  }
}
