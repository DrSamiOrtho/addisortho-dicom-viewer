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

/**
 * Where the visible segments are and how opaque they are drawn, for the CPU picking and selection:
 * the segmentation of the shaders, label voxels or smoothed occupancy as the renderer has it, with
 * the colour LUT the user set, see {@link SegVolumeField}.
 */
@FunctionalInterface
public interface SegmentField {

  /**
   * Opacity, in [0, 1], of the visible segment at the texture coordinate as its colour LUT entry
   * gives it; 0 outside every visible segment.
   */
  double opacity(Vector3d texCoord);

  /** Most memory the field may take during a selection: its caches and its scratch. */
  default long workingBytes() {
    return 0;
  }

  /** Whether the texture coordinate lies in a segment the user has visible. */
  default boolean insideVisible(Vector3d texCoord) {
    return opacity(texCoord) > 0;
  }

  /** No segmentation: nothing is inside. */
  SegmentField NONE = t -> 0;
}
