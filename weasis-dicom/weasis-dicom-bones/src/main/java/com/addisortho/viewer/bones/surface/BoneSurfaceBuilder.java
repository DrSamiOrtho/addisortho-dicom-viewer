/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

import com.addisortho.viewer.bones.mesh.MarchingCubes;
import com.addisortho.viewer.bones.mesh.MeshPieces;
import com.addisortho.viewer.bones.mesh.MeshPieces.Piece;
import com.addisortho.viewer.bones.mesh.TaubinSmoothing;
import com.addisortho.viewer.bones.mesh.TriangleMesh;
import com.addisortho.viewer.bones.mesh.VertexClustering;
import com.addisortho.viewer.bones.surface.Progress.Stage;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * Builds the surface of one segmented bone: the smoothed {@link BoneField}, marching cubes,
 * optionally the largest piece only, Taubin smoothing and optional reduction. Coordinates stay in
 * mm in DICOM patient space (LPS).
 */
public final class BoneSurfaceBuilder {

  /**
   * @param mesh the surface; empty when nothing reaches the threshold
   * @param fromCt {@code false} when built from the label alone
   * @param iso iso value of the field (HU with CT)
   * @param spacingMm spacing of the working grid actually used
   * @param piecesFound pieces of the raw surface, cavities included
   * @param piecesKept pieces left in {@code mesh}
   */
  public record Result(
      TriangleMesh mesh,
      boolean fromCt,
      double iso,
      double spacingMm,
      int piecesFound,
      int piecesKept) {}

  private BoneSurfaceBuilder() {}

  /**
   * @param bone box holding every voxel of the bone label
   * @param ct the CT, or {@code null} to use the label alone
   * @throws CancellationException when the thread is interrupted
   */
  public static Result build(
      LabelSampler labels, Box bone, HuSampler ct, SurfaceOptions options, Progress progress) {
    Thread caller = Thread.currentThread();
    RawSurface raw = rawSurface(labels, bone, ct, options, progress);
    TriangleMesh mesh = raw.mesh();

    progress.update(Stage.PIECES, 0);
    List<Piece> pieces = MeshPieces.split(mesh);
    int kept = pieces.size();
    if (options.keepLargestPiece() && kept > 1) {
      mesh = mesh.subset(pieces.getFirst().triangles());
      kept = 1;
    }
    BoneField.checkCancelled(caller);

    progress.update(Stage.SMOOTHING_SURFACE, 0);
    mesh = TaubinSmoothing.smooth(mesh, options.smoothingIterations());
    progress.update(Stage.REDUCING, 0);
    mesh = VertexClustering.decimate(mesh, options.maxTriangles());
    progress.update(Stage.REDUCING, 1);
    return new Result(mesh, raw.fromCt(), raw.iso(), raw.spacingMm(), pieces.size(), kept);
  }

  private record RawSurface(TriangleMesh mesh, boolean fromCt, double iso, double spacingMm) {}

  /** Marching cubes on the field; the field is garbage once this returns. */
  private static RawSurface rawSurface(
      LabelSampler labels, Box bone, HuSampler ct, SurfaceOptions options, Progress progress) {
    BoneField field = BoneField.build(labels, bone, ct, options, progress);
    progress.update(Stage.SURFACE, 0);
    TriangleMesh mesh = MarchingCubes.extract(field.grid(), field.iso());
    return new RawSurface(mesh, field.fromCt(), field.iso(), field.grid().spacingX());
  }
}
