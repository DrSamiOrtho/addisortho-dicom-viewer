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

/**
 * Values of the texture voxels, in the units the preset and window/level apply to (HU): the field
 * the renderer samples, see {@link TextureField}.
 */
public interface VoxelSampler {

  /** Value of the texture voxel {@code (x, y, z)}, {@link Double#NaN} outside the texture. */
  double valueAt(int x, int y, int z);

  /** Most memory the sampler's caches may hold, for the budget of a selection. */
  default long workingBytes() {
    return 0;
  }

  /** Trilinear value at a fractional texture voxel, as GL_LINEAR, {@link Double#NaN} outside. */
  default double interpolate(double x, double y, double z) {
    int x0 = (int) Math.floor(x);
    int y0 = (int) Math.floor(y);
    int z0 = (int) Math.floor(z);
    double fx = x - x0;
    double fy = y - y0;
    double fz = z - z0;
    double v000 = valueAt(x0, y0, z0);
    if (Double.isNaN(v000)) {
      return Double.NaN;
    }
    double v100 = fx > 0 ? valueOr(x0 + 1, y0, z0, v000) : v000;
    double v010 = fy > 0 ? valueOr(x0, y0 + 1, z0, v000) : v000;
    double v110 = fx > 0 && fy > 0 ? valueOr(x0 + 1, y0 + 1, z0, v000) : v100;
    double v001 = fz > 0 ? valueOr(x0, y0, z0 + 1, v000) : v000;
    double v101 = fx > 0 && fz > 0 ? valueOr(x0 + 1, y0, z0 + 1, v000) : v001;
    double v011 = fy > 0 && fz > 0 ? valueOr(x0, y0 + 1, z0 + 1, v000) : v001;
    double v111 = fx > 0 && fy > 0 && fz > 0 ? valueOr(x0 + 1, y0 + 1, z0 + 1, v000) : v101;
    double v00 = v000 + (v100 - v000) * fx;
    double v10 = v010 + (v110 - v010) * fx;
    double v01 = v001 + (v101 - v001) * fx;
    double v11 = v011 + (v111 - v011) * fx;
    double v0 = v00 + (v10 - v00) * fy;
    double v1 = v01 + (v11 - v01) * fy;
    return v0 + (v1 - v0) * fz;
  }

  private double valueOr(int x, int y, int z, double fallback) {
    double v = valueAt(x, y, z);
    return Double.isNaN(v) ? fallback : v;
  }
}
