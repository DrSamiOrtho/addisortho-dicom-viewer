/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.stitching;

import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * What a piece looks like, so the user can recognise it before ordering the pieces: a small picture
 * for its row and a larger one for the side panel. Same orientation and polarity as the pieces that
 * get matched (bone bright), so "left half" means the same in the picture and in the match. For
 * recognising only: never used for matching or for the saved stitch.
 */
record PiecePicture(BufferedImage large, BufferedImage thumb) {

  static final int LARGE_PX = 800;
  static final int THUMB_PX = 72;

  static PiecePicture of(Piece piece) throws IOException {
    Spacing s = piece.spacing;
    return of(piece.read(), piece.width, piece.height, s.colMm(), s.rowMm());
  }

  static PiecePicture of(float[] values, int width, int height, double colMm, double rowMm) {
    double longMm = Math.max(width * colMm, height * rowMm);
    double largeMm = longMm / LARGE_PX;
    Gray large = Gray.resample(values, width, height, colMm, rowMm, largeMm).normalized();
    Gray thumb =
        Gray.resample(
            large.data(), large.width(), large.height(), largeMm, largeMm, longMm / THUMB_PX);
    return new PiecePicture(
        ImagePanel.gray(large.data(), large.width(), large.height()),
        ImagePanel.gray(thumb.data(), thumb.width(), thumb.height()));
  }
}
