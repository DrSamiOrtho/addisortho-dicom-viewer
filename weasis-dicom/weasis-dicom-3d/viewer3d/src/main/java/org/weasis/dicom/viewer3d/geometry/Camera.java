/*
 * Copyright (c) 2022 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.dicom.viewer3d.geometry;

import static org.joml.Math.abs;
import static org.joml.Math.sqrt;
import static org.joml.Math.tan;
import static org.joml.Math.toRadians;

import java.awt.geom.Point2D;
import java.util.Objects;
import org.joml.Math;
import org.joml.Matrix3d;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector2i;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.weasis.core.api.gui.util.ActionW;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.vr.DicomVolTexture;
import org.weasis.dicom.viewer3d.vr.View3d;
import org.weasis.dicom.viewer3d.vr.VolumeCanvas;

public class Camera {
  public static final String P_DEFAULT_ORIENTATION = "default.vr.orientation";
  public static final double SCALE_MAX = 6.0;
  public static final double SCALE_MIN = 1.0 / SCALE_MAX;
  static final double INITIAL_FOV = 45;
  static final double NEAR_PLANE = 0.1;
  static final double DEFAULT_ZOOM = 1;
  static final Vector3d POSITION_ZERO = new Vector3d();

  private final VolumeCanvas renderer;
  private boolean isAdjusting = false;
  private final Vector3d position = new Vector3d();
  private final Quaterniond rotation = new Quaterniond();

  /** World point the rotation and the zoom centre on; zero is the centre of the volume. */
  private final Vector3d pivot = new Vector3d();

  /** Shift of the image on screen, in units of the view half height (y up). */
  private final Vector2d pan = new Vector2d();

  /** Extra view-space depth of the pivot, from pivot moves that kept the image where it was. */
  private double depthOffset;

  private final Vector3d prevMousePos = new Vector3d();
  // Perspective
  private double zNear = NEAR_PLANE;
  private double zFar = 10000;
  private double fov = INITIAL_FOV;

  // Zoom
  private double zoomFactor = DEFAULT_ZOOM;
  private double internalZoomFactor = -fov * zNear / DEFAULT_ZOOM;

  // Matrix
  public static final Matrix4d currentModelMatrix =
      new Matrix4d().rotateX(Math.toRadians(90)).translate(new Vector3d(-0.5, -0.5, -0.5));
  Matrix4d viewMatrix = null;
  Matrix4d projectionMatrix = null;
  Matrix4d viewProjectionMatrix = null;
  private boolean orthographicProjection;
  private Axis rotationAxis = Axis.Z;

  public Camera(VolumeCanvas renderer) {
    this(renderer, getDefaultOrientation());
  }

  public Camera(VolumeCanvas renderer, View preset) {
    this.renderer = Objects.requireNonNull(renderer);
    setCameraView(preset);
  }

  public static CameraView getDefaultOrientation() {
    String orientation =
        GuiUtils.getUICore().getSystemPreferences().getProperty(P_DEFAULT_ORIENTATION);
    if (StringUtil.hasText(orientation)) {
      return CameraView.getCameraView(orientation);
    }
    return CameraView.INITIAL;
  }

  public static Quaterniond getQuaternion(int x, int y, int z) {
    return new Quaterniond().rotationXYZ(Math.toRadians(x), Math.toRadians(y), Math.toRadians(z));
  }

  public void setCameraView(View preset) {
    if (renderer instanceof View3d view3d) {
      DicomVolTexture volTexture = view3d.getVolTexture();
      if (volTexture != null) {
        set(preset.position(), preset.rotation(volTexture.getSlicePlan()), preset.zoom());
        return;
      }
    }
    set(preset.position(), preset.rotation(), preset.zoom());
  }

  public Camera(VolumeCanvas renderer, Vector3d eye, Vector3d viewCenter, Vector3d upDir) {
    this(renderer, presetFromLookAt(eye, viewCenter, upDir));
  }

  public static View presetFromLookAt(Vector3d eye, Vector3d center, Vector3d up) {
    if (up.y() == 1 && eye.get(1) == 0) {
      eye.y += 1.0e-10f;
    }
    final Vector3d dir = new Vector3d(center).sub(eye);
    final Vector3d zAxis = new Vector3d(dir).normalize();

    final Vector3d xAxis = new Vector3d(zAxis).cross(new Vector3d(up).normalize()).normalize();
    final Vector3d yAxis = new Vector3d(xAxis).cross(zAxis).normalize();
    xAxis.set(new Vector3d(zAxis).cross(yAxis)).normalize();
    return new ViewData(
        "custom", // NON-NLS
        new Vector3d(center).negate(),
        new Quaterniond()
            .setFromNormalized(new Matrix3d(xAxis, yAxis, zAxis).transpose())
            .normalize(),
        -dir.length());
  }

  public Vector3d getPosition() {
    return position;
  }

  public Quaterniond getRotation() {
    return rotation;
  }

  public double getZoomFactor() {
    return zoomFactor;
  }

  public double getzNear() {
    return zNear;
  }

  public boolean isOrthographicProjection() {
    return orthographicProjection;
  }

  public void setOrthographicProjection(boolean orthographicProjection) {
    if (this.orthographicProjection != orthographicProjection) {
      this.orthographicProjection = orthographicProjection;
      resetTransformation();
      setZoomFactor(getZoomFactor());
    }
  }

  public void set(Vector3d position, Quaterniond rotation, double zoom) {
    set(position, rotation, zoom, true);
  }

  public void set(Vector3d position, Quaterniond rotation, double zoom, boolean repaint) {
    this.position.set(position);
    this.rotation.set(rotation);
    updateRotationAction();

    setZoomFactor(zoom, repaint);
  }

  public void updateRotationAction() {
    EventManager.getInstance()
        .getAction(ActionW.ROTATION)
        .ifPresent(s -> s.setSliderValue(getCurrentAxisRotationInDegrees(), false));
  }

  public Matrix4d getViewMatrix() {
    if (viewMatrix == null) {
      // The pivot is brought to the view axis, rotated about, then shifted by the pan: rotation
      // and zoom centre on the pivot wherever the image was panned to.
      double hh = viewHalfHeight();
      viewMatrix =
          new Matrix4d()
              .setTranslation(
                  new Vector3d(pan.x * hh, pan.y * hh, internalZoomFactor + depthOffset))
              .rotate(rotation)
              .translate(new Vector3d(position).sub(pivot));
    }
    return new Matrix4d(viewMatrix);
  }

  /** Half height of the view at the pivot, in world units. */
  public double viewHalfHeight() {
    if (orthographicProjection) {
      return 2.0 / zoomFactor;
    }
    return CameraFraming.viewHalfHeight(abs(internalZoomFactor + depthOffset), fov);
  }

  public Vector3d getPivot() {
    return new Vector3d(pivot);
  }

  public Vector2d getPan() {
    return new Vector2d(pan);
  }

  public double getDepthOffset() {
    return depthOffset;
  }

  public void setPan(Vector2d value) {
    setPan(value, depthOffset);
  }

  /** Sets the pan and the depth offset together, as a snapshot restores them. */
  public void setPan(Vector2d value, double depth) {
    pan.set(value);
    depthOffset = depth;
    updateCameraTransform();
  }

  /**
   * Moves the point the rotation and the zoom centre on. The image does not move at all: the
   * rotated move is folded into the pan and into the depth offset, so every point keeps its view
   * position and its perspective size.
   */
  public void setPivot(Vector3d worldPivot) {
    Vector3d delta = new Vector3d(worldPivot).sub(pivot);
    if (delta.lengthSquared() == 0.0) {
      return;
    }
    // The active projection decides the half height the pan is measured in: distance-dependent in
    // perspective, 2 / zoom in orthographic.
    CameraFraming.PivotRebase rebase =
        orthographicProjection
            ? CameraFraming.rebasePivotOrthographic(
                rotation, delta, pan, viewHalfHeight(), depthOffset)
            : CameraFraming.rebasePivot(
                rotation, delta, pan, abs(internalZoomFactor), depthOffset, fov);
    pan.set(rebase.pan());
    depthOffset = rebase.depthOffset();
    pivot.set(worldPivot);
    updateCameraTransform();
  }

  /** Centres the view on the pivot and zooms so that the box {@code [min, max]} fills it. */
  public void fit(Vector3d min, Vector3d max) {
    pan.set(0, 0);
    depthOffset = 0;
    setZoomFactor(CameraFraming.fitZoom(min, max, rotation, fov, renderer.getAspectRatio()));
  }

  /** Turns the volume about an axis of the screen: x to the right, y up, z towards the viewer. */
  public void rotateAboutView(Vector3d viewAxis, double radians) {
    rotation.set(CameraFraming.rotateAboutViewAxis(rotation, viewAxis, radians));
    updateRotationAction();
    updateCameraTransform();
  }

  /** Rolls the volume about the view axis, counter-clockwise on screen for a positive angle. */
  public void roll(double radians) {
    rotateAboutView(new Vector3d(0, 0, 1), radians);
  }

  /** Pans by a fraction of the view half height, x to the right and y up. */
  public void pan(double dx, double dy) {
    pan.add(dx, dy);
    updateCameraTransform();
  }

  public Matrix4d getProjectionMatrix() {
    if (projectionMatrix == null) {
      projectionMatrix = new Matrix4d();
      if (orthographicProjection) {
        double size = renderer.getSurfaceHeight();
        projectionMatrix.setOrthoSymmetric(renderer.getAspectRatio() * size, size, zFar, 1);
      } else {
        projectionMatrix.setPerspective(fov, renderer.getAspectRatio(), zNear, zFar);
      }
    }
    return new Matrix4d(projectionMatrix);
  }

  public Matrix4d getViewProjectionMatrix() {
    if (viewProjectionMatrix == null) {
      viewProjectionMatrix = new Matrix4d(getProjectionMatrix()).mul(getViewMatrix());
    }
    return new Matrix4d(viewProjectionMatrix);
  }

  public Vector4d getLightOrigin() {
    // Vector3d lightPosition = new Vector3d(0, 0, 0);
    // Matrix4d lightMatrix = new Matrix4d().translate(lightPosition);
    //
    // Matrix4d viewInverse = getViewMatrix().invert();
    // lightMatrix.mul(viewInverse);
    // Vector3d lightTranslation = new Vector3d();
    // lightMatrix.getTranslation(lightTranslation);
    // return new Vector4d(lightTranslation, 1);
    return new Vector4d(0, 0, 0, 1).mul(getViewMatrix().invert());
  }

  public boolean isAdjusting() {
    return isAdjusting;
  }

  public void setAdjusting(boolean adjusting) {
    isAdjusting = adjusting;
  }

  public double getFieldOfView() {
    return fov;
  }

  public double getFocalLength() {
    return 1.0 / tan(toRadians(getFieldOfView()) / 2.0);
  }

  public void init(Point2D p) {
    prevMousePos.x = p.getX();
    prevMousePos.y = p.getY();
    updateCameraTransform();
  }

  /**
   * Anchor the next pan/rotate delta at {@code p} without triggering a render. Used when a sync
   * event needs to align a target view's reference point before the dragging stream starts.
   */
  public void anchorMouse(Point2D p) {
    prevMousePos.x = p.getX();
    prevMousePos.y = p.getY();
  }

  public void setZoomFactor(double zoomFactor) {
    setZoomFactor(zoomFactor, true);
  }

  public void setZoomFactor(double zoomFactor, boolean repaint) {
    renderer.setActionsInView(ActionW.ZOOM.cmd(), zoomFactor);
    this.zoomFactor = Math.abs(zoomFactor);
    double ratio = this.zoomFactor;
    if (orthographicProjection) {
      ratio /= 3.5;
    }
    this.internalZoomFactor = -fov * zNear / ratio;

    if (repaint) {
      updateCameraTransform();
    } else {
      resetTransformation();
    }
  }

  public void setRotation(int degree) {
    Vector3d angles = getEulerAnglesXYZ();
    if (rotationAxis == Axis.X) {
      if (degree >= 180) {
        angles.x = Math.toRadians(degree - 360.0);
      } else {
        angles.x = Math.toRadians(degree);
      }
    } else if (rotationAxis == Axis.Y) {
      if (degree >= 270) {
        angles.y = Math.toRadians(degree - 360.0);
      } else if (degree > 90) {
        angles.y = Math.toRadians(180.0 - degree);
      } else {
        angles.y = Math.toRadians(degree);
      }
    } else {
      if (degree >= 180) {
        angles.z = Math.toRadians(degree - 360.0);
      } else {
        angles.z = Math.toRadians(degree);
      }
    }
    Quaterniond quat = new Quaterniond().rotationXYZ(angles.x, angles.y, angles.z);
    rotation.set(quat);

    updateCameraTransform();
  }

  public void resetRotation() {
    rotation.set(getDefaultOrientation().rotation());
    updateRotationAction();
    updateCameraTransform();
  }

  public void resetPan() {
    position.set(getDefaultOrientation().position());
    pan.set(0, 0);
    depthOffset = 0;
    updateCameraTransform();
  }

  /** Clears the pan without a repaint, for a reset that sets the rest of the view afterwards. */
  public void clearPan() {
    pan.set(0, 0);
    depthOffset = 0;
    resetTransformation();
  }

  public void resetAll() {
    pan.set(0, 0);
    depthOffset = 0;
    setCameraView(getDefaultOrientation());
  }

  public void translate(Point2D p) {
    final Vector2d translationNDC =
        new Vector2d(screenCoordToNDC(p)).sub(screenCoordToNDC(prevMousePos));
    prevMousePos.x = p.getX();
    prevMousePos.y = p.getY();
    // A pan is a shift on screen: it leaves the pivot where it is in the volume.
    pan.add(translationNDC.x() * renderer.getAspectRatio(), translationNDC.y());
    updateCameraTransform();
  }

  public void rotate(Point2D p) {
    final Vector2i dimensions =
        new Vector2i(renderer.getSurfaceWidth(), renderer.getSurfaceHeight());
    rotation
        .set(
            new Quaterniond(ndcToArcBall(boundlessScreenCoordToNDC(p, dimensions)))
                .mul(ndcToArcBall(boundlessScreenCoordToNDC(prevMousePos, dimensions)))
                .mul(rotation))
        .normalize();
    updateRotationAction();

    prevMousePos.x = p.getX();
    prevMousePos.y = p.getY();
    updateCameraTransform();
  }

  public void updateCameraTransform() {
    resetTransformation();
    renderer.display();
  }

  public void resetTransformation() {
    viewMatrix = null;
    projectionMatrix = null;
    viewProjectionMatrix = null;
  }

  private Vector2d screenCoordToNDC(Point2D p) {
    return screenCoordToNDC(new Vector3d(p.getX(), p.getY(), 0));
  }

  private Vector2d boundlessScreenCoordToNDC(Point2D p, Vector2i dimensions) {
    return boundlessScreenCoordToNDC(new Vector3d(p.getX(), p.getY(), 0), dimensions);
  }

  private Vector2d boundlessScreenCoordToNDC(Vector3d mousePos, Vector2i dimensions) {
    final Vector3d mod = new Vector3d(mousePos);
    mod.x = mod.x() % (dimensions.x());
    mod.y = mod.y() % (dimensions.y());
    if (mousePos.x < 0) {
      mod.x = dimensions.x - abs(mod.x);
    }
    if (mousePos.y < 0) {
      mod.y = dimensions.y - abs(mod.y);
    }
    return screenCoordToNDC(mod);
  }

  private Vector2d screenCoordToNDC(Vector3d mousePos) {
    return new Vector2d(
        mousePos.x() * 2.0 / renderer.getSurfaceWidth() - 1.0,
        1.0 - 2.0 * mousePos.y() / renderer.getSurfaceHeight());
  }

  public static Quaterniond ndcToArcBall(Vector2d p) {
    double dist = p.dot(p);
    if (dist <= 1.0) {
      // Inside the sphere
      return new Quaterniond(p.x(), p.y(), sqrt(1.0 - dist), 0.0);
    } else {
      // Outside the sphere
      final Vector2d proj = new Vector2d(p).normalize();
      return new Quaterniond(proj.x(), proj.y(), 0.0, 0.0);
    }
  }

  public Axis getRotationAxis() {
    return rotationAxis;
  }

  public void setRotationAxis(Axis rotationAxis) {
    this.rotationAxis = rotationAxis;
  }

  public Vector3d getEulerAnglesXYZ() {
    Vector3d angles = new Vector3d();
    rotation.getEulerAnglesXYZ(angles);
    return angles;
  }

  public double getCurrentAxisRotation() {
    Vector3d angles = getEulerAnglesXYZ();
    if (rotationAxis == Axis.X) {
      return angles.x;
    } else if (rotationAxis == Axis.Y) {
      return angles.y;
    } else {
      return angles.z;
    }
  }

  public int getCurrentAxisRotationInDegrees() {
    int rotationAngle = (int) Math.round(Math.toDegrees(getCurrentAxisRotation()));
    if (rotationAngle < 0) {
      rotationAngle = (rotationAngle + 360) % 360;
    }
    return rotationAngle;
  }
}
