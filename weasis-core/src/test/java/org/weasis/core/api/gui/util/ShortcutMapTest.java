/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.api.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The shared keys of SHORTCUTS.md, as the viewer registers them. */
class ShortcutMapTest {
  private static final int CTRL = KeyEvent.CTRL_MASK;
  private static final int SHIFT = KeyEvent.SHIFT_MASK;
  private static final int ALT = KeyEvent.ALT_MASK;
  private static ShortcutManager sm;

  @BeforeAll
  static void load() {
    sm = ShortcutManager.getInstance();
    sm.registerDefaults();
  }

  private static void key(String id, int keyCode, int modifier) {
    assertTrue(sm.matches(id, keyCode, modifier), id);
  }

  @Test
  void toolsAreRadiAntsKeys() {
    assertEquals(KeyEvent.VK_W, ActionW.WINLEVEL.getKeyCode());
    assertEquals(KeyEvent.VK_M, ActionW.PAN.getKeyCode());
    assertEquals(KeyEvent.VK_Z, ActionW.ZOOM.getKeyCode());
    assertEquals(KeyEvent.VK_B, ActionW.SCROLL_SERIES.getKeyCode());
    assertEquals(0, ActionW.MEASURE.getKeyCode());
    assertEquals(0, ActionW.DRAW.getKeyCode());
    key(ShortcutManager.ID_GRAPHIC_LINE, KeyEvent.VK_L, 0);
    key(ShortcutManager.ID_GRAPHIC_ANGLE, KeyEvent.VK_A, 0);
    key(ShortcutManager.ID_GRAPHIC_COBB, KeyEvent.VK_C, 0);
    key(ShortcutManager.ID_GRAPHIC_ELLIPSE, KeyEvent.VK_E, 0);
    key(ShortcutManager.ID_GRAPHIC_POLYGON, KeyEvent.VK_G, 0);
    key(ShortcutManager.ID_GRAPHIC_POLYLINE, KeyEvent.VK_O, 0);
    key(ShortcutManager.ID_GRAPHIC_ANNOTATION, KeyEvent.VK_T, 0);
  }

  @Test
  void viewKeys() {
    key(ShortcutManager.ID_VIEWER_FULLSCREEN, KeyEvent.VK_F, 0);
    key(ShortcutManager.ID_VIEWER_INVERT, KeyEvent.VK_F11, 0);
    key(ShortcutManager.ID_VIEWER_TOGGLE_INFO, KeyEvent.VK_F12, 0);
    key(ShortcutManager.ID_VIEWER_BEST_FIT, KeyEvent.VK_0, CTRL);
    key(ShortcutManager.ID_VIEWER_BEST_FIT, KeyEvent.VK_NUMPAD0, CTRL);
    key(ShortcutManager.ID_VIEWER_REAL_SIZE, KeyEvent.VK_1, CTRL);
    key(ShortcutManager.ID_VIEWER_ZOOM_IN, KeyEvent.VK_EQUALS, CTRL);
    key(ShortcutManager.ID_VIEWER_ZOOM_IN, KeyEvent.VK_ADD, CTRL);
    key(ShortcutManager.ID_VIEWER_ZOOM_IN, KeyEvent.VK_EQUALS, CTRL | SHIFT);
    key(ShortcutManager.ID_VIEWER_ZOOM_OUT, KeyEvent.VK_MINUS, CTRL);
    key(ShortcutManager.ID_VIEWER_ZOOM_OUT, KeyEvent.VK_SUBTRACT, CTRL);
    key(ShortcutManager.ID_VIEWER_ESCAPE, KeyEvent.VK_BACK_SLASH, CTRL | SHIFT);
    key(ShortcutManager.ID_DRAW_DESELECT_ALL, KeyEvent.VK_ESCAPE, 0);
    key(ShortcutManager.ID_VIEWER_PRINT, KeyEvent.VK_P, CTRL);
    assertFalse(sm.matches(ShortcutManager.ID_VIEWER_PRINT, KeyEvent.VK_P, 0));
    assertFalse(sm.matches(ShortcutManager.ID_VIEWER_ESCAPE, KeyEvent.VK_ESCAPE, 0));
  }

  @Test
  void navigationKeys() {
    key(ShortcutManager.ID_VIEWER_SCROLL_UP, KeyEvent.VK_UP, 0);
    key(ShortcutManager.ID_VIEWER_SCROLL_UP_FAST, KeyEvent.VK_PAGE_UP, 0);
    key(ShortcutManager.ID_DICOM_PREV_SERIES, KeyEvent.VK_LEFT, 0);
    key(ShortcutManager.ID_DICOM_FIRST_SERIES, KeyEvent.VK_HOME, 0);
    key(ShortcutManager.ID_VIEWER_SCROLL_FIRST, KeyEvent.VK_HOME, SHIFT);
    key(ShortcutManager.ID_PAN_LEFT, KeyEvent.VK_LEFT, CTRL);
    key(ShortcutManager.ID_DICOM_PREV_STUDY, KeyEvent.VK_LEFT, ALT);
    key(ShortcutManager.ID_VIEWER_ROTATE_LEFT, KeyEvent.VK_OPEN_BRACKET, CTRL);
    key(ShortcutManager.ID_VIEWER_FLIP_VERTICAL, KeyEvent.VK_CLOSE_BRACKET, CTRL | SHIFT);
  }

  @Test
  void noKeyDoesTwoJobs() {
    String[] ids = {
      ShortcutManager.ID_GRAPHIC_LINE,
      ShortcutManager.ID_GRAPHIC_ANGLE,
      ShortcutManager.ID_GRAPHIC_COBB,
      ShortcutManager.ID_GRAPHIC_ELLIPSE,
      ShortcutManager.ID_GRAPHIC_POLYGON,
      ShortcutManager.ID_GRAPHIC_POLYLINE,
      ShortcutManager.ID_GRAPHIC_ANNOTATION,
      ShortcutManager.ID_VIEWER_FULLSCREEN,
      ShortcutManager.ID_VIEWER_INVERT,
      ShortcutManager.ID_VIEWER_TOGGLE_INFO,
      ShortcutManager.ID_VIEWER_PRINT,
      ActionW.CINESTART.cmd(),
      ActionW.WINLEVEL.cmd(),
      ActionW.PAN.cmd(),
      ActionW.ZOOM.cmd(),
      ActionW.SCROLL_SERIES.cmd()
    };
    for (String id : ids) {
      assertTrue(
          sm.findConflicts(id, sm.getKeyCode(id), sm.getModifier(id)).isEmpty(),
          id + " shares its key");
    }
  }
}
