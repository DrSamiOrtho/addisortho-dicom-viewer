/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import com.addisortho.viewer.bones.grow.AutoSplit;
import com.addisortho.viewer.bones.grow.VoxelBox;
import javax.swing.SwingWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Finds every bone of the CT at once, off the event dispatch thread: see {@link AutoSplit}. */
final class FindWorker extends SwingWorker<AutoSplit.Result, Void> {

  private static final Logger LOGGER = LoggerFactory.getLogger(FindWorker.class);

  private final CtSource ct;
  private final VoxelBox box;
  private final AutoSplit.Shown shown;

  FindWorker(CtSource ct, VoxelBox box, AutoSplit.Shown shown) {
    this.ct = ct;
    this.box = box;
    this.shown = shown;
  }

  CtSource ct() {
    return ct;
  }

  @Override
  protected AutoSplit.Result doInBackground() {
    AutoSplit.Options options =
        AutoSplit.Options.of(
            ct.grid().columnSpacing(), ct.grid().rowSpacing(), ct.grid().sliceSpacing());
    AutoSplit.Result r = AutoSplit.split(box, ct::huSource, shown, options);
    LOGGER.info(
        "Bones found: {} bones, {} fragments joined, {} specks dropped, grid factor {}, {} ms",
        r.pieces().size(),
        r.fragments(),
        r.specks(),
        r.factor(),
        r.millis());
    return r;
  }
}
