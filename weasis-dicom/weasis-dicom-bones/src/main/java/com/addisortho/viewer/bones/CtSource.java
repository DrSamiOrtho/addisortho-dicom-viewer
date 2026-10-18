/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.BoneExportWorker.Problem;
import com.addisortho.viewer.bones.grow.HuSource;
import com.addisortho.viewer.bones.grow.VoxelBox;
import com.addisortho.viewer.bones.seg.CtGrid;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.joml.Vector3d;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.dicom.codec.DcmMediaReader;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.display.Modality;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.dicom.viewer3d.vr.CropBox;
import org.weasis.dicom.viewer3d.vr.DicomVolTexture;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * The CT of a 3D view as the region grow sees it: the grid of its images as acquired (so the
 * segmentation frames match the CT slices one to one), with HU read from the 3D view's volume.
 */
final class CtSource {

  private final MediaSeries<DicomImageElement> series;
  private final Volume<?, ?> volume;
  private final CtGrid grid;
  private final Attributes attributes;

  private CtSource(
      MediaSeries<DicomImageElement> series,
      Volume<?, ?> volume,
      CtGrid grid,
      Attributes attributes) {
    this.series = series;
    this.volume = volume;
    this.grid = grid;
    this.attributes = attributes;
  }

  /**
   * @throws Problem when the view shows no CT, or a CT the region grow cannot use
   */
  static CtSource of(View3d view) throws Problem {
    DicomVolTexture texture = view.getVolTexture();
    if (texture == null || texture.getVolume() == null || texture.getSeries() == null) {
      throw new Problem(Messages.getString("BoneGrowTool.noCt"));
    }
    Volume<?, ?> volume = texture.getVolume();
    if (texture.getModality() != Modality.CT) {
      throw new Problem(Messages.getString("BoneGrowTool.notCt"));
    }
    if (volume.isBasic() || volume.getStack() == null) {
      throw new Problem(Messages.getString("BoneGrowTool.notRectified"));
    }
    List<DicomImageElement> images = volume.getStack().getSourceStack();
    List<CtGrid.Image> geometry = new ArrayList<>(images.size());
    for (DicomImageElement image : images) {
      geometry.add(geometryOf(image));
    }
    CtGrid grid =
        CtGrid.of(geometry)
            .orElseThrow(() -> new Problem(Messages.getString("BoneGrowTool.irregular")));
    Attributes attributes =
        images.getFirst().getMediaReader() instanceof DcmMediaReader reader
            ? reader.getDicomObject()
            : null;
    if (attributes == null) {
      throw new Problem(Messages.getString("BoneGrowTool.noCt"));
    }
    return new CtSource(texture.getSeries(), volume, grid, attributes);
  }

  private static CtGrid.Image geometryOf(DicomImageElement image) {
    Integer frames = TagD.getTagValue(image, Tag.NumberOfFrames, Integer.class);
    int frame = frames != null && frames > 1 && image.getKey() instanceof Integer i ? i + 1 : 0;
    Integer columns = TagD.getTagValue(image, Tag.Columns, Integer.class);
    Integer rows = TagD.getTagValue(image, Tag.Rows, Integer.class);
    return new CtGrid.Image(
        columns == null ? 0 : columns,
        rows == null ? 0 : rows,
        TagD.getTagValue(image, Tag.ImageOrientationPatient, double[].class),
        TagD.getTagValue(image, Tag.PixelSpacing, double[].class),
        TagD.getTagValue(image, Tag.ImagePositionPatient, double[].class),
        TagD.getTagValue(image, Tag.SOPClassUID, String.class),
        TagD.getTagValue(image, Tag.SOPInstanceUID, String.class),
        frame);
  }

  MediaSeries<DicomImageElement> series() {
    return series;
  }

  Volume<?, ?> volume() {
    return volume;
  }

  CtGrid grid() {
    return grid;
  }

  /** Attributes of the first image: patient, study, series and frame of reference. */
  Attributes attributes() {
    return attributes;
  }

  String seriesUID() {
    return attributes.getString(Tag.SeriesInstanceUID);
  }

  String frameOfReferenceUID() {
    return attributes.getString(Tag.FrameOfReferenceUID);
  }

  /**
   * Nearest-voxel HU of the 3D view's volume (modality LUT output) at each voxel of the grid. Not
   * thread-safe: one instance per growing thread.
   */
  HuSource huSource() {
    int n = grid.sliceCount();
    double[][] origins = new double[n][];
    for (int z = 0; z < n; z++) {
      origins[z] = grid.slice(z).position();
    }
    double[] r = grid.rowDirection();
    double[] c = grid.columnDirection();
    double[] stepX = {
      r[0] * grid.columnSpacing(), r[1] * grid.columnSpacing(), r[2] * grid.columnSpacing()
    };
    double[] stepY = {c[0] * grid.rowSpacing(), c[1] * grid.rowSpacing(), c[2] * grid.rowSpacing()};
    int columns = grid.columns();
    int rows = grid.rows();
    Vector3d p = new Vector3d();
    return (x, y, z) -> {
      if (x < 0 || y < 0 || z < 0 || x >= columns || y >= rows || z >= n) {
        return Double.NaN;
      }
      double[] o = origins[z];
      p.set(
          o[0] + x * stepX[0] + y * stepY[0],
          o[1] + x * stepX[1] + y * stepY[1],
          o[2] + x * stepX[2] + y * stepY[2]);
      volume.lpsToVoxel(p, p);
      return volume.getNearestDouble(p.x, p.y, p.z, 0);
    };
  }

  /** The voxel of the grid nearest to a patient position, when inside the grid. */
  Optional<int[]> voxelOf(Vector3d lps) {
    return grid.nearestVoxel(new double[] {lps.x, lps.y, lps.z});
  }

  /**
   * The block of the grid inside the 3D view's crop box; nothing when the crop is off.
   *
   * <p>The crop box is in normalised texture coordinates of the volume: x and y follow the voxel
   * axes, z runs the other way (the texture is uploaded last slice first).
   *
   * @throws Problem when the crop box holds no voxel of the grid
   */
  Optional<VoxelBox> cropBox(View3d view) throws Problem {
    CropBox box = view.getCropBox();
    if (!view.isCropEnabled() || box.isFull()) {
      return Optional.empty();
    }
    double sx = volume.getSizeX();
    double sy = volume.getSizeY();
    double sz = volume.getSizeZ();
    double[] vx = {box.minX() * sx - 0.5, box.maxX() * sx - 0.5};
    double[] vy = {box.minY() * sy - 0.5, box.maxY() * sy - 0.5};
    double[] vz = {(1 - box.maxZ()) * sz - 0.5, (1 - box.minZ()) * sz - 0.5};
    double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
    double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
    Vector3d p = new Vector3d();
    for (int corner = 0; corner < 8; corner++) {
      volume.voxelToLps(vx[corner & 1], vy[(corner >> 1) & 1], vz[(corner >> 2) & 1], p);
      min[0] = Math.min(min[0], p.x);
      min[1] = Math.min(min[1], p.y);
      min[2] = Math.min(min[2], p.z);
      max[0] = Math.max(max[0], p.x);
      max[1] = Math.max(max[1], p.y);
      max[2] = Math.max(max[2], p.z);
    }
    return Optional.of(
        grid.boxOf(min, max)
            .orElseThrow(() -> new Problem(Messages.getString("BoneGrowTool.cropEmpty"))));
  }
}
