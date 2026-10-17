/*
 * Copyright (c) 2026 Addis Ortho and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.core.ui.util;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Component;
import java.awt.Insets;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import org.weasis.core.api.gui.util.DropDownButton;
import org.weasis.core.api.gui.util.GuiUtils;

/**
 * Adds short RadiAnt-style text labels under toolbar icons. The label is chosen from the
 * "ao.label" client property, the drop-down type, or the SVG file name of the icon (set by {@link
 * org.weasis.core.api.util.ResourceUtil} as the icon description). Buttons that already have text
 * or no known label are left untouched.
 */
public final class ToolbarLabels {
  public static final String PREF_KEY = "weasis.toolbar.labels";
  public static final String CLIENT_PROPERTY = "ao.label";

  private static final String ORIGINAL_MARGIN = "ao.label.margin";

  /** Label by DropDownButton type. */
  private static final Map<String, String> BY_TYPE =
      Map.ofEntries(
          Map.entry("layout", "Layout"),
          Map.entry("zoom", "Zoom"),
          Map.entry("synch", "Sync"),
          Map.entry("winLevel", "W/L"),
          Map.entry("lut", "LUT"),
          Map.entry("vol.lut", "LUT"),
          Map.entry("measure", "Measure"),
          Map.entry("draw", "Draw"),
          Map.entry("crosshair.cut.mode", "Cut"),
          Map.entry("left", "Left"),
          Map.entry("middle", "Middle"),
          Map.entry("right", "Right"),
          Map.entry("wheel", "Wheel"));

  /** Label by SVG file name (without folder and extension). */
  private static final Map<String, String> BY_ICON =
      Map.ofEntries(
          Map.entry("volume", "3D"),
          Map.entry("view3D", "MPR"),
          Map.entry("veins", "MIP"),
          Map.entry("loadVolume", "Rebuild"),
          Map.entry("orthographic", "Ortho"),
          Map.entry("volumeCut", "Cut"),
          Map.entry("volumeSettings", "3D set"),
          Map.entry("reset", "Reset"),
          Map.entry("rotation", "Rotate"),
          Map.entry("flip", "Flip"),
          Map.entry("inverseLut", "Invert"),
          Map.entry("zoomPan", "Lens"),
          Map.entry("metadata", "Info"),
          Map.entry("exportImage", "Image"),
          Map.entry("exportDicom", "Export"),
          Map.entry("importDicom", "Import"),
          Map.entry("importCd", "CD"),
          Map.entry("importImage", "Import"),
          Map.entry("print", "Print"),
          Map.entry("volumeSlicing", "Slice"),
          Map.entry("launch", "Launch"));

  private static final List<WeakReference<AbstractButton>> DECORATED = new ArrayList<>();
  private static boolean enabled = true;
  private static boolean loaded;

  private ToolbarLabels() {}

  public static boolean isEnabled() {
    return enabled;
  }

  /** Switch labels on or off for all toolbars, live. */
  public static void setEnabled(boolean on) {
    enabled = on;
    loaded = true;
    List<AbstractButton> buttons = new ArrayList<>();
    synchronized (DECORATED) {
      DECORATED.removeIf(r -> r.get() == null);
      DECORATED.forEach(r -> buttons.add(r.get()));
    }
    for (AbstractButton b : buttons) {
      if (b != null) {
        apply(b);
      }
    }
    try {
      Component c = GuiUtils.getUICore().getToolbarContainer();
      if (c != null) {
        SwingUtilities.invokeLater(
            () -> {
              c.invalidate();
              c.revalidate();
              c.repaint();
            });
      }
    } catch (RuntimeException | LinkageError e) {
      // UI core not ready: nothing to refresh
    }
  }

  static void decorate(AbstractButton button) {
    if (!loaded) {
      loaded = true;
      try {
        enabled = GuiUtils.getUICore().getSystemPreferences().getBooleanProperty(PREF_KEY, true);
      } catch (RuntimeException | LinkageError e) {
        enabled = true;
      }
    }
    if (button.getClientProperty(CLIENT_PROPERTY) == null) {
      String label = findLabel(button);
      if (label == null) {
        return;
      }
      button.putClientProperty(CLIENT_PROPERTY, label);
      synchronized (DECORATED) {
        DECORATED.add(new WeakReference<>(button));
      }
    }
    apply(button);
  }

  private static void apply(AbstractButton b) {
    Object label = b.getClientProperty(CLIENT_PROPERTY);
    if (!(label instanceof String text)) {
      return;
    }
    if (enabled) {
      if (b.getClientProperty(ORIGINAL_MARGIN) == null) {
        Insets m = b.getMargin();
        b.putClientProperty(ORIGINAL_MARGIN, m == null ? new Insets(0, 0, 0, 0) : m);
      }
      b.setText(text);
      b.setVerticalTextPosition(SwingConstants.BOTTOM);
      b.setHorizontalTextPosition(SwingConstants.CENTER);
      b.setIconTextGap(1);
      b.setMargin(new Insets(2, 3, 2, 3));
      b.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
    } else {
      b.setText(null);
      b.setIconTextGap(4);
      if (b.getClientProperty(ORIGINAL_MARGIN) instanceof Insets m) {
        b.setMargin(m);
      }
      b.putClientProperty(FlatClientProperties.STYLE_CLASS, null);
    }
  }

  private static String findLabel(AbstractButton b) {
    String existing = b.getText();
    if (existing != null && !existing.isBlank()) {
      return null;
    }
    if (b instanceof DropDownButton d) {
      return BY_TYPE.get(d.getType());
    }
    Icon icon = b.getIcon();
    if (icon instanceof javax.swing.ImageIcon ii && ii.getDescription() != null) {
      String name = ii.getDescription();
      name = name.substring(name.lastIndexOf('/') + 1);
      if (name.endsWith(".svg")) {
        name = name.substring(0, name.length() - 4);
      }
      return BY_ICON.get(name);
    }
    return null;
  }
}
