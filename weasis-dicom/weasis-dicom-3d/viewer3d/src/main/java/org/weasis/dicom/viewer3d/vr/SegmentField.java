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
 * Where the visible segments are, for the CPU picking and selection: the segmentation texture of
 * the shaders with the visibility of the segment colour LUT applied, see {@link SegVolumeField}.
 */
@FunctionalInterface
public interface SegmentField {

  /** Whether the texture coordinate lies in a segment the user has visible. */
  boolean insideVisible(Vector3d texCoord);

  /** No segmentation: nothing is inside. */
  SegmentField NONE = t -> false;
}
