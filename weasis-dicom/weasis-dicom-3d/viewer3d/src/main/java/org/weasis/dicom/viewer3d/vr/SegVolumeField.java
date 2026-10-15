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

import java.util.LinkedHashMap;
import java.util.Map;
import org.joml.Vector3d;
import org.weasis.dicom.codec.seg.SegmentationVolume;

/**
 * The rendered segmentation as a {@link SegmentField}: the storage ID of the nearest label voxel,
 * read as the shaders' {@code segVoxel()} reads it (the segmentation texture is uploaded last slice
 * first, like the volume), tested against the visibility of the segment colour LUT. The label
 * slices are read on demand into a small cache; one instance per operation.
 */
public final class SegVolumeField implements SegmentField {

  private static final int SLICE_CACHE = 32;

  private final SegmentationVolume volume;
  private final boolean[] visible;
  private final Map<Integer, int[]> slices =
      new LinkedHashMap<>(SLICE_CACHE * 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, int[]> eldest) {
          return size() > SLICE_CACHE;
        }
      };

  /**
   * @param visible visibility by storage ID, as {@code SegOccupancy.visibleIds} reads it from the
   *     colour LUT
   */
  public SegVolumeField(SegmentationVolume volume, boolean[] visible) {
    this.volume = volume;
    this.visible = visible.clone();
  }

  @Override
  public boolean insideVisible(Vector3d t) {
    // segVoxel(): round(texCoord * segSize - 0.5), with z reversed by the upload.
    int sx = volume.getSizeX();
    int sy = volume.getSizeY();
    int sz = volume.getSizeZ();
    int x = (int) Math.round(t.x * sx - 0.5);
    int y = (int) Math.round(t.y * sy - 0.5);
    int texZ = (int) Math.round(t.z * sz - 0.5);
    if (x < 0 || y < 0 || texZ < 0 || x >= sx || y >= sy || texZ >= sz) {
      return false;
    }
    int[] slice = slice(sz - 1 - texZ);
    if (slice == null) {
      return false;
    }
    int id = slice[y * sx + x];
    return id > 0 && id < visible.length && visible[id];
  }

  private int[] slice(int z) {
    int[] cached = slices.get(z);
    if (cached == null && !slices.containsKey(z)) {
      cached = volume.isDisposed() ? null : volume.exportSliceBitmask(z);
      slices.put(z, cached);
    }
    return cached;
  }
}
