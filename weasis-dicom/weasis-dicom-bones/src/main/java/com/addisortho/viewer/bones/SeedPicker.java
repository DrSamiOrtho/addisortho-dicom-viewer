/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.awt.AWTEvent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.dcm4che3.data.Tag;
import org.joml.Vector3d;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.geometry.GeometryOfSlice;
import org.weasis.dicom.viewer2d.View2d;
import org.weasis.dicom.viewer2d.mpr.MprController;
import org.weasis.dicom.viewer2d.mpr.MprView;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Takes points of the CT in patient space for the Bones tools: the point clicked while armed, on
 * the bone surface of the 3D view, in an MPR view (axial, coronal, sagittal) or on a slice of the
 * plain 2D viewer.
 */
final class SeedPicker {

  /** Farthest a click may move between press and release, in pixels; more is a drag. */
  private static final int CLICK_TOLERANCE = 4;

  /** Least move between two points of a stroke, in pixels. */
  private static final int STROKE_STEP = 2;

  /**
   * A seed position.
   *
   * @param lps patient position, mm
   * @param frameOfReferenceUID frame of reference of the MPR series it was taken on
   * @param plane the view it was taken in, for the user
   * @param additive Shift, Ctrl or Cmd was held: the click adds to what is selected
   * @param normal unit normal of the slice the seed was taken on, in patient axes; {@code null} in
   *     the 3D view
   */
  record Seed(
      Vector3d lps, String frameOfReferenceUID, String plane, boolean additive, Vector3d normal) {

    Seed(Vector3d lps, String frameOfReferenceUID, String plane) {
      this(lps, frameOfReferenceUID, plane, false, null);
    }

    Seed withAdditive(boolean add) {
      return new Seed(lps, frameOfReferenceUID, plane, add, normal);
    }

    /** The seed with the unit normal of the slice it was taken on, in patient axes. */
    Seed withNormal(Vector3d sliceNormal) {
      return new Seed(lps, frameOfReferenceUID, plane, additive, sliceNormal);
    }
  }

  private AWTEventListener listener;
  private Component pressedView;
  private Point pressedAt;

  /** The points of the stroke being drawn on a slice, or {@code null}. */
  private List<Seed> stroke;

  private Point strokeLast;

  /** Told the stroke so far each time a drag adds a point to it, or {@code null}. */
  private Consumer<List<Seed>> strokeGrows;

  private BooleanSupplier strokesOn = () -> false;

  /** Radius of the brush in mm, or 0 when no brush is on: the ring drawn under the mouse. */
  private DoubleSupplier ringMm = () -> 0;

  private final BrushRing ring = new BrushRing();

  /** The press has moved beyond a click since it went down: a drag, even if it ends near it. */
  private boolean dragged;

  boolean isArmed() {
    return listener != null;
  }

  /**
   * Hands every left click on a 3D view or an MPR view to {@code onPick} as a patient position, on
   * the event dispatch thread, until disarmed. A press and release at the same place is a click; a
   * drag still rotates the 3D view or moves through the MPR as usual. The views receive the click
   * too, so the 3D view's left button should hold a tool that does nothing on a click, such as
   * rotation. A click in the 3D view that meets nothing is reported to {@code onMiss}.
   */
  void arm(Consumer<Seed> onPick, Consumer<String> onMiss) {
    arm(onPick, null, null, () -> false, () -> 0, onMiss);
  }

  /**
   * As {@link #arm(Consumer, Consumer)}, and while {@code strokes} says so, a drag on a slice (an
   * MPR view or the 2D viewer) is handed to {@code onStroke} on release as the patient positions it
   * passed through, in order, and to {@code onStrokeGrows} each time it gains a point on the way. A
   * drag in the 3D view stays a rotation. While {@code brushMm} gives a radius, a ring of that size
   * follows the mouse over the slices: what a click there would work on.
   */
  void arm(
      Consumer<Seed> onPick,
      Consumer<List<Seed>> onStroke,
      Consumer<List<Seed>> onStrokeGrows,
      BooleanSupplier strokes,
      DoubleSupplier brushMm,
      Consumer<String> onMiss) {
    disarm();
    strokeGrows = onStrokeGrows;
    strokesOn = strokes;
    ringMm = brushMm;
    listener =
        event -> {
          if (!(event instanceof MouseEvent e)) {
            return;
          }
          followRing(e);
          if ((e.getModifiersEx() & InputEvent.ALT_DOWN_MASK) != 0) {
            return;
          }
          if (e.getID() == MouseEvent.MOUSE_DRAGGED) {
            followStroke(e);
            return;
          }
          if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
          }
          Component c = e.getComponent();
          Component view = view3dOf(c);
          if (view == null) {
            view = mprViewOf(c);
          }
          if (view == null) {
            view = view2dOf(c);
          }
          if (view == null) {
            return;
          }
          Point p = SwingUtilities.convertPoint(c, e.getPoint(), view);
          if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            pressedView = view;
            pressedAt = p;
            dragged = false;
            stroke = null;
            if (onStroke != null && strokes.getAsBoolean() && !(view instanceof View3d)) {
              stroke = new ArrayList<>();
              strokeLast = p;
              sliceSeed(view, p).ifPresent(stroke::add);
            }
            return;
          }
          if (e.getID() != MouseEvent.MOUSE_RELEASED) {
            return;
          }
          boolean click =
              view == pressedView
                  && pressedAt != null
                  && !dragged
                  && pressedAt.distance(p) <= CLICK_TOLERANCE;
          List<Seed> drawn = stroke;
          if (drawn != null && view == pressedView) {
            sliceSeed(view, p).ifPresent(drawn::add); // the stroke ends where it is released
          }
          pressedView = null;
          pressedAt = null;
          stroke = null;
          strokeLast = null;
          boolean additive =
              (e.getModifiersEx()
                      & (InputEvent.SHIFT_DOWN_MASK
                          | InputEvent.CTRL_DOWN_MASK
                          | InputEvent.META_DOWN_MASK))
                  != 0;
          if (!click) {
            if (drawn != null && !drawn.isEmpty() && strokes.getAsBoolean()) {
              onStroke.accept(drawn.stream().map(s -> s.withAdditive(additive)).toList());
            }
            return;
          }
          Consumer<Seed> pick = seed -> onPick.accept(seed.withAdditive(additive));
          if (view instanceof View3d view3d) {
            pick3d(view3d, p, pick, onMiss);
          } else if (view instanceof MprView mpr) {
            fromClick(mpr, p)
                .ifPresentOrElse(
                    pick, () -> onMiss.accept(Messages.getString("BoneGrowTool.missed")));
          } else if (view instanceof View2d slice) {
            fromSliceClick(slice, p)
                .ifPresentOrElse(
                    pick, () -> onMiss.accept(Messages.getString("BoneGrowTool.missed")));
          }
        };
    Toolkit.getDefaultToolkit()
        .addAWTEventListener(
            listener, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
  }

  /** Adds the point dragged over to the stroke being drawn, on the slice it started on. */
  private void followStroke(MouseEvent e) {
    if (pressedView == null || !SwingUtilities.isLeftMouseButton(e)) {
      return;
    }
    Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), pressedView);
    if (pressedAt != null && pressedAt.distance(p) > CLICK_TOLERANCE) {
      dragged = true;
    }
    if (stroke == null) {
      return;
    }
    if (strokeLast != null && strokeLast.distance(p) < STROKE_STEP) {
      return;
    }
    strokeLast = p;
    sliceSeed(pressedView, p).ifPresent(stroke::add);
    if (dragged && strokeGrows != null && strokesOn.getAsBoolean()) {
      strokeGrows.accept(List.copyOf(stroke));
    }
  }

  /**
   * The patient position of a point of an MPR view or of a slice of the 2D viewer, with the normal
   * of the slice.
   */
  private static Optional<Seed> sliceSeed(Component view, Point p) {
    Optional<Seed> seed = slicePoint(view, p);
    if (seed.isEmpty()) {
      return seed;
    }
    Optional<Seed> right = slicePoint(view, new Point(p.x + PROBE, p.y));
    Optional<Seed> down = slicePoint(view, new Point(p.x, p.y + PROBE));
    if (right.isEmpty() || down.isEmpty()) {
      return seed;
    }
    Vector3d normal =
        right
            .get()
            .lps()
            .sub(seed.get().lps(), new Vector3d())
            .cross(down.get().lps().sub(seed.get().lps(), new Vector3d()));
    return normal.lengthSquared() > 0 ? seed.map(s -> s.withNormal(normal.normalize())) : seed;
  }

  private static Optional<Seed> slicePoint(Component view, Point p) {
    if (view instanceof MprView mpr) {
      return fromClick(mpr, p);
    }
    if (view instanceof View2d slice) {
      return fromSliceClick(slice, p);
    }
    return Optional.empty();
  }

  /** Screen pixels between the points a slice's scale and normal are taken from. */
  private static final int PROBE = 16;

  /** Pixels of the view to a mm of the patient at the point, or 0 when it is not on a slice. */
  private static double pixelsPerMm(Component view, Point p) {
    Optional<Seed> here = slicePoint(view, p);
    Optional<Seed> right = slicePoint(view, new Point(p.x + PROBE, p.y));
    if (here.isEmpty() || right.isEmpty()) {
      return 0;
    }
    double mm = here.get().lps().distance(right.get().lps());
    return mm > 0 ? PROBE / mm : 0;
  }

  /** Keeps the brush's ring under the mouse while it is over a slice and a brush is on. */
  private void followRing(MouseEvent e) {
    int id = e.getID();
    if (id != MouseEvent.MOUSE_MOVED
        && id != MouseEvent.MOUSE_DRAGGED
        && id != MouseEvent.MOUSE_ENTERED
        && id != MouseEvent.MOUSE_EXITED) {
      return;
    }
    double mm = ringMm.getAsDouble();
    Component c = e.getComponent();
    Component view = mprViewOf(c);
    if (view == null) {
      view = view2dOf(c);
    }
    if (mm <= 0 || id == MouseEvent.MOUSE_EXITED) {
      if (mm <= 0 || view == null || view == ring.getParent()) {
        ring.takeOff();
      }
      return;
    }
    if (!(view instanceof JComponent pane)) {
      return;
    }
    Point p = SwingUtilities.convertPoint(c, e.getPoint(), view);
    double scale = pixelsPerMm(view, p);
    if (scale <= 0) {
      ring.takeOff();
      return;
    }
    ring.show(pane, p, (int) Math.round(mm * scale));
  }

  /** Takes the brush's ring off the slices, as when the brush is put down. */
  void hideRing() {
    ring.takeOff();
  }

  /**
   * The outline of the brush on a slice: a ring that follows the mouse. It lies over the view as a
   * component that takes no mouse event, so the view below works as before.
   */
  private static final class BrushRing extends JComponent {

    private static final Color RING = new Color(0xA8FF30);
    private static final int PAD = 3;

    BrushRing() {
      setOpaque(false);
    }

    void show(JComponent view, Point centre, int radius) {
      int r = Math.max(2, radius);
      if (getParent() != view) {
        takeOff();
        view.add(this, 0);
      }
      setBounds(centre.x - r - PAD, centre.y - r - PAD, 2 * (r + PAD) + 1, 2 * (r + PAD) + 1);
      repaint();
    }

    void takeOff() {
      Container parent = getParent();
      if (parent != null) {
        java.awt.Rectangle was = getBounds();
        parent.remove(this);
        parent.repaint(was.x, was.y, was.width, was.height);
      }
    }

    @Override
    public boolean contains(int x, int y) {
      return false; // every mouse event is the view's
    }

    @Override
    protected void paintComponent(Graphics g) {
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      int d = getWidth() - 2 * PAD - 1;
      g2.setColor(Color.BLACK);
      g2.setStroke(new BasicStroke(3f));
      g2.drawOval(PAD, PAD, d, d);
      g2.setColor(RING);
      g2.setStroke(new BasicStroke(1.4f));
      g2.drawOval(PAD, PAD, d, d);
      g2.dispose();
    }
  }

  /** The surface the 3D view shows under the point, through the view's own picking. */
  private void pick3d(View3d view, Point p, Consumer<Seed> onPick, Consumer<String> onMiss) {
    String unavailable = view.pickUnavailableReason();
    if (unavailable != null) {
      onMiss.accept(unavailable);
      return;
    }
    AWTEventListener armed = listener;
    view.pickAsync(
        p,
        hit -> {
          if (listener != armed) {
            return; // disarmed or armed again meanwhile
          }
          Optional<Vector3d> lps = hit.flatMap(view::lpsOf);
          Volume<?, ?> volume =
              view.getVolTexture() == null ? null : view.getVolTexture().getVolume();
          if (lps.isEmpty() || volume == null) {
            onMiss.accept(Messages.getString("BoneGrowTool.missed"));
            return;
          }
          onPick.accept(new Seed(lps.get(), frameOfReferenceOf(volume), "3D")); // NON-NLS
        });
  }

  private static View3d view3dOf(Component component) {
    if (component instanceof View3d view) {
      return view;
    }
    return component == null
        ? null
        : (View3d) SwingUtilities.getAncestorOfClass(View3d.class, component);
  }

  void disarm() {
    if (listener != null) {
      Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
      listener = null;
    }
    pressedView = null;
    pressedAt = null;
    stroke = null;
    strokeLast = null;
    dragged = false;
    ring.takeOff();
  }

  private static MprView mprViewOf(Component component) {
    if (component instanceof MprView view) {
      return view;
    }
    return component == null
        ? null
        : (MprView) SwingUtilities.getAncestorOfClass(MprView.class, component);
  }

  /** The plain 2D view holding the component; an MPR view is not one (it has its own volume). */
  private static View2d view2dOf(Component component) {
    View2d view =
        component instanceof View2d v
            ? v
            : component == null
                ? null
                : (View2d) SwingUtilities.getAncestorOfClass(View2d.class, component);
    return view instanceof MprView ? null : view;
  }

  /**
   * Patient position of a point of a slice of the plain 2D viewer, through the slice's own geometry
   * (Image Position and Orientation), as the cross-hair does.
   */
  static Optional<Seed> fromSliceClick(View2d view, Point point) {
    DicomImageElement image = view.getImage();
    GeometryOfSlice geometry = image == null ? null : image.getSliceGeometry();
    Point2D pixel = view.getImageCoordinatesFromMouse(point.x, point.y);
    if (geometry == null || pixel == null || Double.isNaN(pixel.getX())) {
      return Optional.empty();
    }
    Vector3d lps = geometry.getPosition(pixel);
    String frame = TagD.getTagValue(image, Tag.FrameOfReferenceUID, String.class);
    return Optional.of(new Seed(lps, frame, "2D")); // NON-NLS
  }

  /** Patient position of a point of an MPR view, through the transform the view draws with. */
  static Optional<Seed> fromClick(MprView view, Point point) {
    MprController controller = view.getMprController();
    Volume<?, ?> volume = controller == null ? null : controller.getVolume();
    if (volume == null || volume.isBasic()) {
      return Optional.empty();
    }
    Point2D image = view.getImageCoordinatesFromMouse(point.x, point.y);
    Vector3d scaled =
        image == null ? null : view.getScaledVolumeCoordinatesFromImage(image.getX(), image.getY());
    if (scaled == null) {
      return Optional.empty();
    }
    Vector3d voxel = scaled.div(volume.getVoxelRatio());
    return seedAt(volume, voxel, view.getPlane().name());
  }

  private static Optional<Seed> seedAt(Volume<?, ?> volume, Vector3d voxel, String plane) {
    try {
      Vector3d lps = volume.voxelToLps(voxel.x, voxel.y, voxel.z, new Vector3d());
      return Optional.of(new Seed(lps, frameOfReferenceOf(volume), plane));
    } catch (UnsupportedOperationException e) {
      return Optional.empty();
    }
  }

  static String frameOfReferenceOf(Volume<?, ?> volume) {
    if (volume.getStack() == null) {
      return null;
    }
    MediaSeries<DicomImageElement> series = volume.getStack().getSeries();
    String uid = TagD.getTagValue(series, Tag.FrameOfReferenceUID, String.class);
    if (uid == null && volume.getStack().getMiddleImage() != null) {
      uid =
          TagD.getTagValue(
              volume.getStack().getMiddleImage(), Tag.FrameOfReferenceUID, String.class);
    }
    return uid;
  }
}
