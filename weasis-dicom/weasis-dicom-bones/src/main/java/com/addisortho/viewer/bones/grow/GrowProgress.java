/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/** Progress of a region grow. Called from worker threads. */
@FunctionalInterface
public interface GrowProgress {

  GrowProgress NONE = (_, _) -> {};

  /** Steps of the grow, in order. */
  enum Stage {
    GROWING,
    SEPARATING,
    CLOSING,
    FILLING,
    PIECES
  }

  /**
   * @param fraction completed part of {@code stage}, from 0 to 1
   */
  void update(Stage stage, double fraction);
}
