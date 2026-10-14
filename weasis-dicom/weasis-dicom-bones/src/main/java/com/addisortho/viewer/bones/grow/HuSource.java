/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/** CT value in HU at a voxel of the working grid. Called from the growing thread only. */
@FunctionalInterface
public interface HuSource {

  /**
   * @return the value, or {@code NaN} where the CT has none
   */
  double huAt(int x, int y, int z);
}
