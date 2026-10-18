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
package org.weasis.dicom.viewer3d.vr;

import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.geometry.Camera;

/**
 * The state of a 3D view that a snapshot (K) keeps and the previous/next snapshot keys bring back:
 * the camera, the preset and window, the shading and the crop box. The cuts are not part of it;
 * they have their own undo history.
 */
public record ViewSnapshot(
    Quaterniond rotation,
    Vector3d position,
    Vector3d pivot,
    Vector2d pan,
    double depthOffset,
    double zoom,
    Preset preset,
    int windowWidth,
    int windowCenter,
    boolean shading,
    double opacity,
    boolean cropEnabled,
    CropBox cropBox) {

  public static ViewSnapshot of(View3d view) {
    Camera c = view.getCamera();
    RenderingLayer<?> layer = view.getRenderingLayer();
    return new ViewSnapshot(
        new Quaterniond(c.getRotation()),
        new Vector3d(c.getPosition()),
        c.getPivot(),
        c.getPan(),
        c.getDepthOffset(),
        c.getZoomFactor(),
        view.getVolumePreset(),
        layer.getWindowWidth(),
        layer.getWindowCenter(),
        layer.isShading(),
        layer.getOpacity(),
        view.isCropEnabled(),
        view.getCropBox());
  }

  public void applyTo(View3d view) {
    RenderingLayer<?> layer = view.getRenderingLayer();
    layer.setEnableRepaint(false);
    if (view.getVolumePreset() != preset) {
      view.setVolumePreset(preset);
    }
    layer.setWindowWidth(windowWidth);
    layer.setWindowCenter(windowCenter);
    layer.setShading(shading);
    layer.setOpacity(opacity);
    layer.setEnableRepaint(true);
    view.getEventManager()
        .getAction(ActionVol.VOL_SHADING)
        .ifPresent(a -> a.setSelectedWithoutTriggerAction(shading));
    view.setCropBox(cropEnabled, cropBox, false);
    Camera c = view.getCamera();
    c.setPivot(pivot);
    c.setPan(pan, depthOffset);
    c.set(position, rotation, zoom, false);
    view.getEventManager().getAction(ActionW.ZOOM).ifPresent(a -> a.setRealValue(zoom, false));
    c.updateCameraTransform();
  }
}
