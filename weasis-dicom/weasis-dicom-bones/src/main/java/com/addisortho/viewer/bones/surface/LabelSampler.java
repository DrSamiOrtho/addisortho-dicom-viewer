/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

/**
 * Segmentation label at a patient position in mm (DICOM LPS). Must be safe to call from any thread.
 */
@FunctionalInterface
public interface LabelSampler {

  /** Not segmented. */
  int NONE = 0;

  /** The bone being exported. */
  int BONE = 1;

  /** Another segment, which the grown bone must not enter. */
  int OTHER = 2;

  /**
   * @return {@link #NONE}, {@link #BONE} or {@link #OTHER}
   */
  int labelAt(double x, double y, double z);
}
