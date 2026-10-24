/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.BoxMask;
import com.addisortho.viewer.bones.grow.MaskEdit;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingWorker;

/** Smooths the surface of some bones in the background; a bone that would vanish is kept as is. */
final class SmoothWorker extends SwingWorker<List<Bone>, Void> {

  /** Majority rounds: pits and spikes of about this many voxels are smoothed away. */
  static final int RADIUS = 1;

  private final List<Bone> bones;

  SmoothWorker(List<Bone> bones) {
    this.bones = List.copyOf(bones);
  }

  /** The bones as they were when the work started. */
  List<Bone> bones() {
    return bones;
  }

  @Override
  protected List<Bone> doInBackground() {
    List<Bone> out = new ArrayList<>();
    for (Bone b : bones) {
      BoxMask smooth = MaskEdit.smooth(b.mask(), RADIUS, Thread.currentThread());
      out.add(smooth == null ? b : b.withMask(smooth));
    }
    return out;
  }
}
