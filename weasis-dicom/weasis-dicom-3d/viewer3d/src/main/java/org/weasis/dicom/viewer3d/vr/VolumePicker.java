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

  /**
   * @param cutAway tells whether a texture coordinate is cut away (kept mask, crosshair cut), so
   *     the ray passes through it
   */
  public VolumePicker(
      VolumeSpace space,
      VoxelSampler sampler,
      TransferFunction transfer,
      Predicate<Vector3d> cutAway) {
    this.space = space;
    this.sampler = sampler;
    this.transfer = transfer;
    this.cutAway = cutAway;
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

  /** Picks along a ray given in world units. */
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
    Vector3d voxel = space.gridVoxelWorldSize(new Vector3d());
    double step = 0.5 * Math.min(voxel.x, Math.min(voxel.y, voxel.z));
    Vector3d world = new Vector3d();
    Vector3d tex = new Vector3d();
    Vector3d vol = new Vector3d();
    double accumulated = 0.0;
    double previous = tmin;
    for (double t = tmin + step * 0.5; t <= tmax; t += step) {
      world.set(direction).mul(t).add(origin);
      space.texCoordOfWorld(world, tex);
      if (outsideUnitCube(tex) || cutAway.test(tex)) {
        previous = t;
        continue;
      }
      space.volumeOfTexCoord(tex, vol);
      double value = sampler.interpolate(vol.x, vol.y, vol.z);
      if (Double.isNaN(value)) {
        previous = t;
        continue;
      }
      double alpha = transfer.opacity(value);
      if (alpha > 0.0) {
        // One in-plane voxel is the unit the preset opacities are given for, as in the shaders.
        double steps = (t - previous) / voxel.x;
        double corrected = 1.0 - Math.pow(1.0 - Math.min(alpha, 0.9999), steps);
        accumulated += (1.0 - accumulated) * corrected;
        if (accumulated >= HIT_OPACITY) {
          Vector3i grid = space.gridOfTexCoord(tex, new Vector3i());
          return Optional.of(new Hit(new Vector3d(tex), grid, new Vector3d(vol), t));
        }
      }
      previous = t;
    }
    return Optional.empty();
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
