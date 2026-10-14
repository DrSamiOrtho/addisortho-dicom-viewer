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

import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * The coordinate systems of a rendered volume and the conversions between them.
 *
 * <ul>
 *   <li><b>texture</b>: normalised {@code [0, 1]³}, what the shaders sample. Texture z runs from
 *       the last axial slice (z = 0) to the first one, see {@code VolumeBuilder}.
 *   <li><b>world</b>: the shaders' ray space, {@code [-texelSize, texelSize]} on each axis where
 *       {@code texelSize} is the normalised texel size of the volume texture.
 *   <li><b>grid</b>: a voxel grid laid over the texture, the one of the kept mask, which may be
 *       coarser than the texture.
 *   <li><b>volume</b>: the voxel indices of the CPU {@code Volume}, fractional, where the slices
 *       are resampled to the texture by a centre-aligned scale.
 * </ul>
 *
 * @param grid size of the voxel grid
 * @param volume size of the CPU volume
 * @param texelSize normalised texel size of the texture, as the {@code texelSize} uniform
 */
public record VolumeSpace(Vector3i grid, Vector3i volume, Vector3d texelSize) {

  public VolumeSpace {
    grid = new Vector3i(grid);
    volume = new Vector3i(volume);
    texelSize = new Vector3d(texelSize);
    if (grid.x <= 0 || grid.y <= 0 || grid.z <= 0) {
      throw new IllegalArgumentException("Empty grid " + grid);
    }
    if (volume.x <= 0 || volume.y <= 0 || volume.z <= 0) {
      throw new IllegalArgumentException("Empty volume " + volume);
    }
  }

  /** A space whose grid is the volume itself and whose texture is the volume scaled to a cube. */
  public static VolumeSpace ofVolume(Vector3i size, Vector3d spacing) {
    double maxExtent =
        Math.max(size.x * spacing.x, Math.max(size.y * spacing.y, size.z * spacing.z));
    Vector3d texel =
        new Vector3d(
            size.x * spacing.x / maxExtent,
            size.y * spacing.y / maxExtent,
            size.z * spacing.z / maxExtent);
    return new VolumeSpace(size, size, texel);
  }

  public long gridVoxels() {
    return (long) grid.x * grid.y * grid.z;
  }

  /** Texture coordinate of the centre of a grid voxel. */
  public Vector3d texCoordOfGrid(int x, int y, int z, Vector3d dst) {
    return dst.set((x + 0.5) / grid.x, (y + 0.5) / grid.y, (z + 0.5) / grid.z);
  }

  /** Grid voxel holding a texture coordinate, clamped to the grid. */
  public Vector3i gridOfTexCoord(Vector3d t, Vector3i dst) {
    return dst.set(
        Math.clamp((int) Math.floor(t.x * grid.x), 0, grid.x - 1),
        Math.clamp((int) Math.floor(t.y * grid.y), 0, grid.y - 1),
        Math.clamp((int) Math.floor(t.z * grid.z), 0, grid.z - 1));
  }

  /** Fractional volume voxel of a texture coordinate; a voxel centre is at an integer value. */
  public Vector3d volumeOfTexCoord(Vector3d t, Vector3d dst) {
    return dst.set(t.x * volume.x - 0.5, t.y * volume.y - 0.5, (1.0 - t.z) * volume.z - 0.5);
  }

  /** Texture coordinate of a fractional volume voxel. */
  public Vector3d texCoordOfVolume(double vx, double vy, double vz, Vector3d dst) {
    return dst.set((vx + 0.5) / volume.x, (vy + 0.5) / volume.y, 1.0 - (vz + 0.5) / volume.z);
  }

  /** Grid voxel nearest to a volume voxel, clamped to the grid. */
  public Vector3i gridOfVolume(int vx, int vy, int vz, Vector3i dst) {
    Vector3d t = texCoordOfVolume(vx, vy, vz, new Vector3d());
    return gridOfTexCoord(t, dst);
  }

  public Vector3d worldOfTexCoord(Vector3d t, Vector3d dst) {
    return dst.set(
        (2.0 * t.x - 1.0) * texelSize.x,
        (2.0 * t.y - 1.0) * texelSize.y,
        (2.0 * t.z - 1.0) * texelSize.z);
  }

  public Vector3d texCoordOfWorld(Vector3d w, Vector3d dst) {
    return dst.set(
        (w.x / texelSize.x + 1.0) / 2.0,
        (w.y / texelSize.y + 1.0) / 2.0,
        (w.z / texelSize.z + 1.0) / 2.0);
  }

  /** World coordinates of the corner of the crop box, {@code [0, 1]³} being the whole volume. */
  public Vector3d worldOfCrop(double x, double y, double z, Vector3d dst) {
    return worldOfTexCoord(new Vector3d(x, y, z), dst);
  }

  /** Size of one grid voxel in world units. */
  public Vector3d gridVoxelWorldSize(Vector3d dst) {
    return dst.set(
        2.0 * texelSize.x / grid.x, 2.0 * texelSize.y / grid.y, 2.0 * texelSize.z / grid.z);
  }
}
