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
 *   <li><b>texture voxels</b>: the voxels of the volume texture, what the ray marches and the
 *       opacity is corrected by; a slice of the texture is one source slice, chosen by {@link
 *       #sourceSliceOfTextureSlice}, scaled in plane to the texture's width and height.
 *   <li><b>grid</b>: a voxel grid laid over the texture, the one of the kept mask, which may be
 *       coarser than the texture; used for the mask alone.
 *   <li><b>volume</b>: the voxel indices of the CPU {@code Volume}, fractional, where the slices
 *       are resampled to the texture by a centre-aligned scale in plane and by {@link
 *       #sourceSliceOfTextureSlice} across slices.
 * </ul>
 *
 * @param grid size of the mask grid
 * @param texture size of the volume texture
 * @param volume size of the CPU volume
 * @param texelSize normalised texel size of the texture, as the {@code texelSize} uniform
 */
public record VolumeSpace(Vector3i grid, Vector3i texture, Vector3i volume, Vector3d texelSize) {

  public VolumeSpace {
    grid = new Vector3i(grid);
    texture = new Vector3i(texture);
    volume = new Vector3i(volume);
    texelSize = new Vector3d(texelSize);
    if (grid.x <= 0 || grid.y <= 0 || grid.z <= 0) {
      throw new IllegalArgumentException("Empty grid " + grid);
    }
    if (texture.x <= 0 || texture.y <= 0 || texture.z <= 0) {
      throw new IllegalArgumentException("Empty texture " + texture);
    }
    if (volume.x <= 0 || volume.y <= 0 || volume.z <= 0) {
      throw new IllegalArgumentException("Empty volume " + volume);
    }
  }

  /**
   * The source slice held by a texture slice, the one convention of the upload (VolumeBuilder), of
   * the conversions here and of the picking: texture slice 0 holds the last source slice, and a
   * texture shallower than the volume takes every {@code volumeDepth / textureDepth}-th slice.
   */
  public static int sourceSliceOfTextureSlice(int texZ, int textureDepth, int volumeDepth) {
    double step = volumeDepth / (double) textureDepth;
    int i = textureDepth - 1 - Math.clamp(texZ, 0, textureDepth - 1);
    return Math.clamp((int) Math.floor(i * step), 0, volumeDepth - 1);
  }

  /**
   * The texture slice holding the source slice {@code vz}, the exact inverse of the convention
   * above for every uploaded slice (the texture is never deeper than the volume, so {@code ceil(vz
   * / step)} is the one index whose slice starts at or after {@code vz}); a skipped source slice
   * maps to the texture slice holding the next uploaded one.
   */
  public static int textureSliceOfSourceSlice(int vz, int textureDepth, int volumeDepth) {
    double step = volumeDepth / (double) textureDepth;
    int i = (int) Math.ceil(Math.clamp(vz, 0, volumeDepth - 1) / step);
    return Math.clamp(textureDepth - 1 - i, 0, textureDepth - 1);
  }

  /** A space whose texture and grid are the volume itself, scaled to a cube by its spacing. */
  public static VolumeSpace ofVolume(Vector3i size, Vector3d spacing) {
    return ofVolume(size, size, spacing);
  }

  /** A space whose grid is the texture, for a volume scaled to a cube by its spacing. */
  public static VolumeSpace ofVolume(Vector3i texture, Vector3i size, Vector3d spacing) {
    double maxExtent =
        Math.max(size.x * spacing.x, Math.max(size.y * spacing.y, size.z * spacing.z));
    Vector3d texel =
        new Vector3d(
            size.x * spacing.x / maxExtent,
            size.y * spacing.y / maxExtent,
            size.z * spacing.z / maxExtent);
    return new VolumeSpace(texture, texture, size, texel);
  }

  /** A copy with another mask grid. */
  public VolumeSpace withGrid(Vector3i newGrid) {
    return new VolumeSpace(newGrid, texture, volume, texelSize);
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

  /**
   * Fractional volume voxel of a texture coordinate; a voxel centre is at an integer value. In
   * plane this is the centre-aligned scale of the upload; across slices the centre of a texture
   * slice is exactly its source slice ({@link #sourceSliceOfTextureSlice}) and the coordinate runs
   * linearly between the centres.
   */
  public Vector3d volumeOfTexCoord(Vector3d t, Vector3d dst) {
    double tz = t.z * texture.z;
    int texZ = Math.clamp((int) Math.floor(tz), 0, texture.z - 1);
    double step = volume.z / (double) texture.z;
    double vz = sourceSliceOfTextureSlice(texZ, texture.z, volume.z) + (texZ + 0.5 - tz) * step;
    return dst.set(t.x * volume.x - 0.5, t.y * volume.y - 0.5, vz);
  }

  /**
   * Texture coordinate of a fractional volume voxel: a source slice maps to the centre of the
   * texture slice holding it, and the coordinate runs linearly between source slices.
   */
  public Vector3d texCoordOfVolume(double vx, double vy, double vz, Vector3d dst) {
    double step = volume.z / (double) texture.z;
    int nearest = Math.clamp((int) Math.round(vz), 0, volume.z - 1);
    int texZ = textureSliceOfSourceSlice(nearest, texture.z, volume.z);
    double tz = texZ + 0.5 - (vz - nearest) / step;
    return dst.set((vx + 0.5) / volume.x, (vy + 0.5) / volume.y, tz / texture.z);
  }

  /** Grid voxel nearest to a volume voxel, clamped to the grid. */
  public Vector3i gridOfVolume(int vx, int vy, int vz, Vector3i dst) {
    Vector3d t = texCoordOfVolume(vx, vy, vz, new Vector3d());
    return gridOfTexCoord(t, dst);
  }

  /** Fractional texture voxel of a texture coordinate; a voxel centre is at an integer value. */
  public Vector3d textureVoxelOfTexCoord(Vector3d t, Vector3d dst) {
    return dst.set(t.x * texture.x - 0.5, t.y * texture.y - 0.5, t.z * texture.z - 0.5);
  }

  /** Texture coordinate of the centre of a texture voxel. */
  public Vector3d texCoordOfTextureVoxel(int x, int y, int z, Vector3d dst) {
    return dst.set((x + 0.5) / texture.x, (y + 0.5) / texture.y, (z + 0.5) / texture.z);
  }

  /** Texture voxel holding a texture coordinate, clamped to the texture. */
  public Vector3i textureVoxelOf(Vector3d t, Vector3i dst) {
    return dst.set(
        Math.clamp((int) Math.floor(t.x * texture.x), 0, texture.x - 1),
        Math.clamp((int) Math.floor(t.y * texture.y), 0, texture.y - 1),
        Math.clamp((int) Math.floor(t.z * texture.z), 0, texture.z - 1));
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

  /** Size of one texture voxel in world units, the unit of the ray step and opacity correction. */
  public Vector3d textureVoxelWorldSize(Vector3d dst) {
    return dst.set(
        2.0 * texelSize.x / texture.x,
        2.0 * texelSize.y / texture.y,
        2.0 * texelSize.z / texture.z);
  }
}
