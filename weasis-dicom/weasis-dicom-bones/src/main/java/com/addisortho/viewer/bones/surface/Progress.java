/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.surface;

/** Progress of a surface build. Called from worker threads. */
@FunctionalInterface
public interface Progress {

  Progress NONE = (_, _) -> {};

  /** Steps of the build, in order. */
  enum Stage {
    FIELD,
    SMOOTHING_FIELD,
    SURFACE,
    PIECES,
    SMOOTHING_SURFACE,
    REDUCING
  }

  /**
   * @param fraction completed part of {@code stage}, from 0 to 1
   */
  void update(Stage stage, double fraction);
}
