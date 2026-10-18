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

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Optional;
import org.joml.Vector3d;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.Feature;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.image.ImageViewerPlugin;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.viewer2d.ResetTools;
import org.weasis.dicom.viewer3d.geometry.CameraView;
import org.weasis.dicom.viewer3d.vr.LassoCut;
import org.weasis.dicom.viewer3d.vr.View3d;
import org.weasis.dicom.viewer3d.vr.View3dSnapshot;
import org.weasis.dicom.viewer3d.vr.View3dVideoExport;

/**
 * Keys of the 3D view, handled before the common viewer keys so the 3D tools win where they clash:
 *
 * <ul>
 *   <li>Mouse tools: R rotate, T roll, W window/level, M pan, Z zoom, S scalpel, L length, Y arrow.
 *       T is the pan in the 2D viewer and M the measure; in the 3D view the pan is on M.
 *   <li>Arrows rotate (left/right about the screen's vertical axis, up/down about its horizontal
 *       one); Ctrl+arrows pan; &lt; and &gt; roll.
 *   <li>Ctrl+plus / Ctrl+minus zoom, Ctrl+0 fits the visible part, 0 restores the default window,
 *       Ctrl+S toggles the shading, Ctrl+R resets the scene, Ctrl+Alt+0 restores the whole volume.
 *   <li>Scalpel: Delete cuts inside the area, Enter cuts outside, Escape clears the area (then a
 *       pending measure, then it resets the view as before), Ctrl+Z / Ctrl+Y undo and redo a cut or
 *       a crop box change, Ctrl+Shift+Alt+Z clears the history.
 *   <li>1 to 6: anterior, posterior, left, right, superior, inferior, centred (see {@link
 *       StandardView}).
 *   <li>Ctrl+F2 exports a rotation video (RadiAnt's advanced movie).
 *   <li>Ctrl+C copies the image, Ctrl+E saves it as JPEG, F2 starts or stops the quick movie, K
 *       takes a 3D snapshot of the view state, [ and ] show the previous and next one, F12 toggles
 *       the annotations, F shows the view full screen (F11 as before).
 * </ul>
 *
 * The Cmd key stands in for Ctrl on macOS.
 */
public final class Shortcuts3d {

  private static final double ARROW_STEP_DEGREES = 5;
  private static final double PAN_STEP = 0.05;
  private static final double ZOOM_STEP = 1.1;

  private Shortcuts3d() {}

  /**
   * Handles a key pressed in a 3D view.
   *
   * @return {@code true} when the key was one of the 3D view's
   */
  public static boolean handle(View3d view, KeyEvent e) {
    int key = e.getKeyCode();
    int mods = e.getModifiersEx();
    boolean ctrl = (mods & (InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK)) != 0;
    boolean alt = (mods & InputEvent.ALT_DOWN_MASK) != 0;
    boolean shift = (mods & InputEvent.SHIFT_DOWN_MASK) != 0;
    EventManager manager = EventManager.getInstance();

    if (ctrl && alt && !shift && key == KeyEvent.VK_0) {
      view.restoreVolume();
      return true;
    }
    if (ctrl && alt && shift && key == KeyEvent.VK_Z) {
      view.clearCutHistory();
      return true;
    }
    if (ctrl && !alt) {
      return handleControl(view, e, key, shift, manager);
    }
    if (alt) {
      return false;
    }
    return handlePlain(view, key, shift, manager);
  }

  private static boolean handleControl(
      View3d view, KeyEvent e, int key, boolean shift, EventManager manager) {
    switch (key) {
      case KeyEvent.VK_LEFT -> view.getCamera().pan(-PAN_STEP, 0);
      case KeyEvent.VK_RIGHT -> view.getCamera().pan(PAN_STEP, 0);
      case KeyEvent.VK_UP -> view.getCamera().pan(0, PAN_STEP);
      case KeyEvent.VK_DOWN -> view.getCamera().pan(0, -PAN_STEP);
      case KeyEvent.VK_PLUS, KeyEvent.VK_ADD, KeyEvent.VK_EQUALS -> zoomBy(view, ZOOM_STEP);
      case KeyEvent.VK_MINUS, KeyEvent.VK_SUBTRACT -> zoomBy(view, 1 / ZOOM_STEP);
      case KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> view.fitToVisible();
      case KeyEvent.VK_S ->
          manager.getAction(ActionVol.VOL_SHADING).ifPresent(a -> a.setSelected(!a.isSelected()));
      case KeyEvent.VK_R -> manager.reset(ResetTools.ALL);
      case KeyEvent.VK_Z -> {
        if (shift) {
          view.redoEdit();
        } else {
          view.undoEdit();
        }
      }
      case KeyEvent.VK_Y -> view.redoEdit();
      case KeyEvent.VK_F2 -> View3dVideoExport.export(view);
      case KeyEvent.VK_C -> View3dSnapshot.copyToClipboard(view);
      case KeyEvent.VK_E -> View3dSnapshot.saveJpeg(view);
      default -> {
        return false;
      }
    }
    e.consume();
    return true;
  }

  private static boolean handlePlain(View3d view, int key, boolean shift, EventManager manager) {
    switch (key) {
      case KeyEvent.VK_LEFT -> rotate(view, new Vector3d(0, 1, 0), -ARROW_STEP_DEGREES);
      case KeyEvent.VK_RIGHT -> rotate(view, new Vector3d(0, 1, 0), ARROW_STEP_DEGREES);
      case KeyEvent.VK_UP -> rotate(view, new Vector3d(1, 0, 0), -ARROW_STEP_DEGREES);
      case KeyEvent.VK_DOWN -> rotate(view, new Vector3d(1, 0, 0), ARROW_STEP_DEGREES);
      case KeyEvent.VK_COMMA, KeyEvent.VK_LESS ->
          view.getCamera().roll(Math.toRadians(ARROW_STEP_DEGREES));
      case KeyEvent.VK_PERIOD, KeyEvent.VK_GREATER ->
          view.getCamera().roll(-Math.toRadians(ARROW_STEP_DEGREES));
      case KeyEvent.VK_0, KeyEvent.VK_NUMPAD0 -> manager.reset(ResetTools.WL);
      case KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> {
        if (!view.getLasso().isClosed()) {
          return false;
        }
        view.cutLasso(LassoCut.Side.INSIDE);
      }
      case KeyEvent.VK_ENTER -> {
        if (!view.getLasso().isClosed()) {
          return false;
        }
        view.cutLasso(LassoCut.Side.OUTSIDE);
      }
      case KeyEvent.VK_ESCAPE -> {
        if (!view.getLasso().isEmpty()) {
          view.lassoClear();
        } else if (!view.cancelPendingMeasure()) {
          return false; // the common reset of the view
        }
      }
      case KeyEvent.VK_T -> tool(manager, ActionVol.ROLL);
      case KeyEvent.VK_M -> tool(manager, ActionW.PAN);
      case KeyEvent.VK_S -> tool(manager, ActionVol.SCALPEL);
      case KeyEvent.VK_L -> tool(manager, ActionVol.LENGTH);
      case KeyEvent.VK_Y -> tool(manager, ActionVol.ARROW);
      case KeyEvent.VK_F2 -> view.toggleMovie();
      case KeyEvent.VK_K -> view.takeSnapshot();
      case KeyEvent.VK_OPEN_BRACKET -> view.showSnapshot(-1);
      case KeyEvent.VK_CLOSE_BRACKET -> view.showSnapshot(1);
      case KeyEvent.VK_F12 ->
          manager.fireSeriesViewerListeners(
              new SeriesViewerEvent(
                  manager.getSelectedView2dContainer(), null, null, EVENT.TOGGLE_INFO));
      case KeyEvent.VK_F -> {
        if (shift) {
          return false;
        }
        ImageViewerPlugin<DicomImageElement> container = manager.getSelectedView2dContainer();
        if (container != null) {
          container.maximizedSelectedImagePane(container.getSelectedViewCanvas(), null);
        }
      }
      default -> {
        // Plain digits only, as in RadiAnt: Shift+digit is left to the other keys.
        Optional<StandardView> standard = shift ? Optional.empty() : StandardView.ofKey(key);
        if (standard.isEmpty()) {
          return false;
        }
        preset(view, standard.get().cameraView());
      }
    }
    return true;
  }

  private static void rotate(View3d view, Vector3d axis, double degrees) {
    view.getCamera().rotateAboutView(axis, Math.toRadians(degrees));
  }

  private static void zoomBy(View3d view, double factor) {
    double zoom = view.getCamera().getZoomFactor() * factor;
    view.getCamera().setZoomFactor(zoom);
    EventManager.getInstance()
        .getAction(ActionW.ZOOM)
        .ifPresent(a -> a.setRealValue(view.getCamera().getZoomFactor(), false));
  }

  /**
   * Turns the view to a standard orientation and centres it on the pivot (the crop box, or the
   * volume when the crop is off), keeping the zoom, as RadiAnt's 1 to 6 keys do.
   */
  public static void preset(View3d view, CameraView cameraView) {
    view.getCamera().clearPan();
    view.getCamera()
        .set(
            view.getCamera().getPosition(),
            cameraView.rotation(),
            view.getCamera().getZoomFactor());
  }

  /** Puts a tool on the left mouse button. */
  public static void tool(EventManager manager, Feature<?> action) {
    manager.changeLeftMouseAction(action.cmd());
  }

  /** The keys of the 3D view as a two-column HTML table, for the help button and tooltips. */
  public static String helpHtml() {
    String[][] rows = {
      {"1 2 3 4 5 6", Messages.getString("help.views")}, // NON-NLS
      {"R T M Z W", Messages.getString("help.tools")}, // NON-NLS
      {"S", Messages.getString("help.scalpel")}, // NON-NLS
      {"L / Y", Messages.getString("help.measure")}, // NON-NLS
      {"Del / Enter", Messages.getString("help.cut")}, // NON-NLS
      {"Ctrl+Z / Ctrl+Y", Messages.getString("help.undo")}, // NON-NLS
      {"Ctrl+Alt+0", Messages.getString("help.restore")}, // NON-NLS
      {"\u2190 \u2192 \u2191 \u2193  < >", Messages.getString("help.rotate")}, // NON-NLS
      {"Ctrl+arrows", Messages.getString("help.pan")}, // NON-NLS
      {"Ctrl+ + / Ctrl+ -  / Ctrl+0", Messages.getString("help.zoom")}, // NON-NLS
      {"0", Messages.getString("help.window")}, // NON-NLS
      {"Ctrl+S", Messages.getString("help.shading")}, // NON-NLS
      {"Ctrl+R", Messages.getString("help.reset")}, // NON-NLS
      {"F2 / Ctrl+F2", Messages.getString("help.movie")}, // NON-NLS
      {"K  [  ]", Messages.getString("help.snapshot")}, // NON-NLS
      {"Ctrl+C / Ctrl+E", Messages.getString("help.picture")}, // NON-NLS
      {"F / F12", Messages.getString("help.screen")}, // NON-NLS
      {"Alt+drag", Messages.getString("help.alt")} // NON-NLS
    };
    StringBuilder html = new StringBuilder("<html><table cellpadding='2'>"); // NON-NLS
    for (String[] row : rows) {
      html.append("<tr><td><b>") // NON-NLS
          .append(row[0])
          .append("</b></td><td>") // NON-NLS
          .append(row[1])
          .append("</td></tr>"); // NON-NLS
    }
    return html.append("</table></html>").toString(); // NON-NLS
  }
}
