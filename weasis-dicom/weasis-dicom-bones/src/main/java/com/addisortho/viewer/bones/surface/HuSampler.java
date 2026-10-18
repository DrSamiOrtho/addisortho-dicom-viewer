/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

/** CT value in HU at a patient position in mm (DICOM LPS). Must be safe to call from any thread. */
@FunctionalInterface
public interface HuSampler {

  /**
   * @return the interpolated value, or {@code NaN} outside the CT
   */
  double huAt(double x, double y, double z);
}
