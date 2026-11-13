/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.library;

import java.text.MessageFormat;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.explorer.model.DataExplorerModel;
import org.weasis.dicom.explorer.Messages;
import org.weasis.dicom.explorer.exp.ExplorerTask;

/**
 * Shows a library copy in the explorer's loading panel, with its stop button. The copy itself runs
 * on the library's own thread and only this task waits: stopping interrupts the wait, never a write
 * in progress, and the copy ends at the next image and reports what it had kept by then.
 */
final class RetainTask extends ExplorerTask<Void, String> {
  private static final Logger LOGGER = LoggerFactory.getLogger(RetainTask.class);

  /** The copy to run, told whether to stop and where to report progress. */
  interface Job {
    void run(BooleanSupplier cancelled, IntConsumer progress);
  }

  private final DataExplorerModel model;
  private final ExecutorService executor;
  private final Job job;

  RetainTask(DataExplorerModel model, ExecutorService executor, Job job) {
    super(Messages.getString("library.import.running"), false);
    this.model = model;
    this.executor = executor;
    this.job = job;
  }

  @Override
  protected Void doInBackground() {
    fire(ObservableEvent.BasicAction.LOADING_START);
    Consumer<Integer> shown =
        count ->
            firePropertyChange(
                STEP_PROPERTY,
                null,
                MessageFormat.format(Messages.getString("library.import.progress"), count));
    Future<?> copy = executor.submit(() -> job.run(this::isCancelled, shown::accept));
    try {
      copy.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // stopped: the copy sees that and reports by itself
    } catch (ExecutionException e) {
      LOGGER.error("The library copy failed: {}", e.getCause().getClass().getSimpleName());
    }
    return null;
  }

  @Override
  protected void done() {
    fire(ObservableEvent.BasicAction.LOADING_STOP);
  }

  private void fire(ObservableEvent.BasicAction action) {
    if (model != null) {
      model.firePropertyChange(new ObservableEvent(action, model, null, this));
    }
  }
}
