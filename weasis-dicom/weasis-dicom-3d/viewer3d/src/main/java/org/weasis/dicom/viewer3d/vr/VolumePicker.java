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

import java.util.Optional;
import java.util.function.Predicate;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3i;
import org.joml.Vector4d;

/**
 * Finds the first visible point of the volume under a screen point: the ray of the shaders' {@code
 * CreateCameraRay} is marched through the crop box on the CPU, in half voxel steps, with the
 * voxels' opacities composited front to back as the renderer does, until the ray is half opaque.
 * The hit is at the sample that crossed that level, so it is within half a voxel of the surface.
 */
public final class VolumePicker {

  /** Accumulated opacity at which a ray is taken to have hit the surface. */
  public static final double HIT_OPACITY = 0.5;

  /** How the segmentation takes part in the rendering, as the shaders' seg uniforms do. */
  public enum SegMode {
    /** No segmentation: the anatomy alone. */
    NONE,
    /** The segments are drawn over the anatomy: a visible segment is a surface too. */
    OVERLAY,
    /** Only the segments are drawn: the anatomy is not there to be picked. */
    SEG_ONLY,
    /** Mask include: the anatomy is shown inside the visible segments only. */
    MASK_INCLUDE,
    /** Mask exclude: the anatomy is shown outside the visible segments only. */
    MASK_EXCLUDE;

    /** Whether the anatomy's own opacity counts in this mode. */
    public boolean rendersAnatomy() {
      return this != SEG_ONLY;
    }

    /**
     * Whether a texture coordinate inside (or not) a visible segment is cut away from the anatomy.
     */
    public boolean masksAnatomy(boolean insideSegment) {
      return this == MASK_INCLUDE ? !insideSegment : this == MASK_EXCLUDE && insideSegment;
    }

    /** Whether the segments are composited over the anatomy as their own layer in this mode. */
    public boolean segmentIsSurface() {
      return this == OVERLAY || this == SEG_ONLY;
    }
  }

  /**
   * @param texCoord where the ray hit, in texture coordinates
   * @param grid the grid voxel holding it
   * @param volume the volume voxel coordinates (fractional) of the hit
   * @param depth distance from the camera to the hit, in world units
   */
  public record Hit(Vector3d texCoord, Vector3i grid, Vector3d volume, double depth) {}

  private final VolumeSpace space;
  private final VoxelSampler sampler;
  private final TransferFunction transfer;
  private final Predicate<Vector3d> cutAway;
  private final SegMode segMode;
  private final SegmentField segments;
  private java.util.function.BooleanSupplier cancelled = () -> false;

  /**
   * A picker of the anatomy alone.
   *
   * @param cutAway tells whether a texture coordinate is cut away (kept mask, crosshair cut), so
   *     the ray passes through it
   */
  public VolumePicker(
      VolumeSpace space,
      VoxelSampler sampler,
      TransferFunction transfer,
      Predicate<Vector3d> cutAway) {
    this(space, sampler, transfer, cutAway, SegMode.NONE, SegmentField.NONE);
  }

  /**
   * A picker honouring the segmentation display: the mask modes hide the anatomy inside or outside
   * the visible segments, and in the overlay and segmentation-only modes a visible segment is a
   * surface the ray stops on.
   */
  public VolumePicker(
      VolumeSpace space,
      VoxelSampler sampler,
      TransferFunction transfer,
      Predicate<Vector3d> cutAway,
      SegMode segMode,
      SegmentField segments) {
    this.space = space;
    this.sampler = sampler;
    this.transfer = transfer;
    this.cutAway = cutAway;
    this.segMode = segMode;
    this.segments = segments;
  }

  /**
   * Picks under a screen point.
   *
   * @param viewInverse inverse of the camera's view matrix, as the shaders get it
   * @param projectionInverse inverse of the projection matrix, as the shaders get it
   * @param ndcX screen x in [-1, 1]
   * @param ndcY screen y in [-1, 1], up
   * @param cropMin lower corner of the crop box in texture coordinates
   * @param cropMax upper corner of the crop box in texture coordinates
   */
  public Optional<Hit> pick(
      Matrix4d viewInverse,
      Matrix4d projectionInverse,
      double ndcX,
      double ndcY,
      Vector3d cropMin,
      Vector3d cropMax) {
    Vector4d o = viewInverse.transform(new Vector4d(0, 0, 0, 1));
    Vector3d origin = new Vector3d(o.x, o.y, o.z);
    Vector4d d = projectionInverse.transform(new Vector4d(ndcX, ndcY, 0, 1));
    Vector4d dir = viewInverse.transform(new Vector4d(d.x, d.y, d.z, 0));
    Vector3d direction = new Vector3d(dir.x, dir.y, dir.z).normalize();
    return pickRay(origin, direction, cropMin, cropMax);
  }

  /** A pick on a worker checks this between samples and gives up when it says so. */
  public VolumePicker withCancel(java.util.function.BooleanSupplier cancelled) {
    this.cancelled = cancelled;
    return this;
  }

  /** Picks along a ray given in world units; empty when the pick was cancelled. */
  public Optional<Hit> pickRay(
      Vector3d origin, Vector3d direction, Vector3d cropMin, Vector3d cropMax) {
    Vector3d boxMin = space.worldOfTexCoord(cropMin, new Vector3d());
    Vector3d boxMax = space.worldOfTexCoord(cropMax, new Vector3d());
    double[] range = intersect(origin, direction, boxMin, boxMax);
    if (range == null) {
      return Optional.empty();
    }
    double tmin = Math.max(range[0], 0.0);
    double tmax = range[1];
    if (tmax < tmin) {
      return Optional.empty();
    }
    // The ray walks the texture, whatever the resolution of the kept mask laid over it.
    Vector3d voxel = space.textureVoxelWorldSize(new Vector3d());
    double step = 0.5 * Math.min(voxel.x, Math.min(voxel.y, voxel.z));
    // Texture-space distance per world unit along the ray, the unit of the segment opacities.
    Vector3d texel = space.texelSize();
    double texPerWorld =
        Math.sqrt(
            sq(direction.x / (2 * texel.x))
                + sq(direction.y / (2 * texel.y))
                + sq(direction.z / (2 * texel.z)));
    boolean composited = segMode.segmentIsSurface();
    Vector3d world = new Vector3d();
    Vector3d tex = new Vector3d();
    Vector3d texVoxel = new Vector3d();
    double accumulated = 0.0;
    Hit anatomyHit = null;
    // The segments are accumulated on their own along the whole ray and composited over the
    // anatomy, as accumulateSegOverlay() is in the shaders: where they end up half opaque they
    // are what the eye lands on, wherever the anatomy was.
    double segAccumulated = 0.0;
    Hit segmentHit = null;
    Hit segmentEntry = null;
    double previous = tmin;
    for (double t = tmin + step * 0.5; t <= tmax; t += step) {
      if (cancelled.getAsBoolean()) {
        return Optional.empty();
      }
      world.set(direction).mul(t).add(origin);
      space.texCoordOfWorld(world, tex);
      if (outsideUnitCube(tex) || cutAway.test(tex)) {
        previous = t;
        continue;
      }
      double segAlpha = segMode == SegMode.NONE ? 0.0 : segments.opacity(tex);
      boolean inSegment = segAlpha > 0;
      if (composited && inSegment && segmentHit == null) {
        if (segmentEntry == null) {
          segmentEntry = hitAt(tex, t);
        }
        // SEG_OPACITY_REF_DIST: the LUT alpha is the opacity over 0.1 of the texture.
        double segA =
            1.0 - Math.pow(1.0 - Math.min(segAlpha, 0.9999), (t - previous) * texPerWorld / 0.1);
        segAccumulated += (1.0 - segAccumulated) * segA;
        if (segAccumulated >= HIT_OPACITY) {
          segmentHit = hitAt(tex, t);
        }
      }
      double alpha = 0.0;
      if (anatomyHit == null && segMode.rendersAnatomy() && !segMode.masksAnatomy(inSegment)) {
        // The texture's own voxels, trilinearly as the sampler of the shaders.
        space.textureVoxelOfTexCoord(tex, texVoxel);
        double value = sampler.interpolate(texVoxel.x, texVoxel.y, texVoxel.z);
        alpha = Double.isNaN(value) ? 0.0 : transfer.opacity(value);
      }
      if (alpha > 0.0) {
        // One in-plane texture voxel is the unit the preset opacities are given for, as in the
        // shaders' correctedOpacity(); the mask grid plays no part in it.
        double steps = (t - previous) / voxel.x;
        double corrected = 1.0 - Math.pow(1.0 - Math.min(alpha, 0.9999), steps);
        accumulated += (1.0 - accumulated) * corrected;
        if (accumulated >= HIT_OPACITY) {
          anatomyHit = hitAt(tex, t);
          if (!composited) {
            return Optional.of(anatomyHit);
          }
        }
      }
      previous = t;
      if (composited && segmentHit != null && (anatomyHit != null || !segMode.rendersAnatomy())) {
        break; // both layers are settled
      }
    }
    if (!composited) {
      return Optional.empty();
    }
    // The overlay is drawn only where the anatomy ray produced something (pxColor.a > 0).
    boolean overlayShown = !segMode.rendersAnatomy() || accumulated > 0.0;
    if (overlayShown && segmentHit != null) {
      return Optional.of(segmentHit);
    }
    if (anatomyHit != null) {
      return Optional.of(anatomyHit);
    }
    return Optional.ofNullable(overlayShown ? segmentEntry : null);
  }

  private Hit hitAt(Vector3d tex, double t) {
    Vector3i grid = space.gridOfTexCoord(tex, new Vector3i());
    Vector3d vol = space.volumeOfTexCoord(tex, new Vector3d());
    return new Hit(new Vector3d(tex), grid, vol, t);
  }

  private static double sq(double v) {
    return v * v;
  }

  private static boolean outsideUnitCube(Vector3d t) {
    return t.x < 0 || t.x > 1 || t.y < 0 || t.y > 1 || t.z < 0 || t.z > 1;
  }

  /** Entry and exit distances of the ray through the box, or {@code null} when it misses it. */
  static double[] intersect(Vector3d origin, Vector3d dir, Vector3d boxMin, Vector3d boxMax) {
    double tmin = Double.NEGATIVE_INFINITY;
    double tmax = Double.POSITIVE_INFINITY;
    for (int axis = 0; axis < 3; axis++) {
      double o = origin.get(axis);
      double d = dir.get(axis);
      double lo = boxMin.get(axis);
      double hi = boxMax.get(axis);
      if (Math.abs(d) < 1e-12) {
        if (o < lo || o > hi) {
          return null;
        }
        continue;
      }
      double t0 = (lo - o) / d;
      double t1 = (hi - o) / d;
      tmin = Math.max(tmin, Math.min(t0, t1));
      tmax = Math.min(tmax, Math.max(t0, t1));
    }
    return tmax < tmin ? null : new double[] {tmin, tmax};
  }
}
