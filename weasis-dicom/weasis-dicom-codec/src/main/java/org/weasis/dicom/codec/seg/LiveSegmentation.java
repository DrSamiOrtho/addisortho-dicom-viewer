/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.codec.seg;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.dcm4che3.data.Tag;
import org.joml.Vector3d;
import org.weasis.dicom.codec.DicomMediaIO;
import org.weasis.dicom.codec.HiddenSpecialElement;
import org.weasis.dicom.codec.SpecialElementRegion;
import org.weasis.dicom.codec.TagD;

/**
 * A segmentation edited in memory and shown at once, without writing a DICOM SEG file and loading
 * it again: the label volume of the bones of a CT, which the Bones tools change voxel by voxel. Its
 * voxels lie on the grid of the CT's image volume, so the MPR and 3D views draw it as it is,
 * without resampling.
 *
 * <p>While it is registered, the views of that CT show it instead of the SEG series it stands for
 * ({@link #isReplaced}): that file is still written in the background, for saving and export, but
 * is no longer resampled and uploaded after every change.
 */
public final class LiveSegmentation {

  /** Patient position of an image-volume voxel. */
  @FunctionalInterface
  public interface VoxelToLps {
    Vector3d lps(double x, double y, double z);
  }

  /** Farthest, in mm, two grids may be apart at a corner and still be the same grid. */
  private static final double SAME_GRID_MM = 0.05;

  private static final Map<String, LiveSegmentation> BY_SERIES = new ConcurrentHashMap<>();

  private final String seriesUID;
  private final String replacedSegSeriesUID;
  private final SegmentationVolume volume;
  private final Vector3d[] corners;

  /**
   * @param seriesUID the image series the labels belong to
   * @param replacedSegSeriesUID the SEG series this one stands for in the views, or {@code null}
   * @param volume the labels, on the grid of the series' image volume
   * @param voxelToLps the patient position of a voxel of that grid, to recognise the grid
   */
  public LiveSegmentation(
      String seriesUID,
      String replacedSegSeriesUID,
      SegmentationVolume volume,
      VoxelToLps voxelToLps) {
    this.seriesUID = Objects.requireNonNull(seriesUID);
    this.replacedSegSeriesUID = replacedSegSeriesUID;
    this.volume = Objects.requireNonNull(volume);
    this.corners = corners(volume.getSizeX(), volume.getSizeY(), volume.getSizeZ(), voxelToLps);
  }

  public String seriesUID() {
    return seriesUID;
  }

  public SegmentationVolume volume() {
    return volume;
  }

  /**
   * Whether an image volume of this size and geometry is the grid the labels lie on, so they can be
   * drawn over it voxel for voxel.
   */
  public boolean fits(int sizeX, int sizeY, int sizeZ, VoxelToLps voxelToLps) {
    if (sizeX != volume.getSizeX() || sizeY != volume.getSizeY() || sizeZ != volume.getSizeZ()) {
      return false;
    }
    Vector3d[] other = corners(sizeX, sizeY, sizeZ, voxelToLps);
    for (int i = 0; i < corners.length; i++) {
      if (corners[i].distance(other[i]) > SAME_GRID_MM) {
        return false;
      }
    }
    return true;
  }

  private static Vector3d[] corners(int sx, int sy, int sz, VoxelToLps f) {
    return new Vector3d[] {
      new Vector3d(f.lps(0, 0, 0)),
      new Vector3d(f.lps(sx - 1.0, 0, 0)),
      new Vector3d(f.lps(0, sy - 1.0, 0)),
      new Vector3d(f.lps(0, 0, sz - 1.0))
    };
  }

  /** Shows {@code live} in the views of its series, in place of any earlier one. */
  public static void register(LiveSegmentation live) {
    BY_SERIES.put(live.seriesUID, live);
  }

  /** Stops showing the live labels of the series; its SEG files are shown again. */
  public static void unregister(String seriesUID) {
    if (seriesUID != null) {
      BY_SERIES.remove(seriesUID);
    }
  }

  public static Optional<LiveSegmentation> of(String seriesUID) {
    return seriesUID == null ? Optional.empty() : Optional.ofNullable(BY_SERIES.get(seriesUID));
  }

  /** Whether {@code seg} is a SEG file a live segmentation stands for, so views leave it out. */
  public static boolean isReplaced(SpecialElementRegion seg) {
    if (BY_SERIES.isEmpty() || !(seg instanceof HiddenSpecialElement element)) {
      return false;
    }
    String uid = TagD.getTagValue(element, Tag.SeriesInstanceUID, String.class);
    if (uid == null && element.getMediaReader() instanceof DicomMediaIO reader) {
      uid = reader.getDicomObject().getString(Tag.SeriesInstanceUID);
    }
    if (uid == null) {
      return false;
    }
    for (LiveSegmentation live : BY_SERIES.values()) {
      if (uid.equals(live.replacedSegSeriesUID)) {
        return true;
      }
    }
    return false;
  }
}
