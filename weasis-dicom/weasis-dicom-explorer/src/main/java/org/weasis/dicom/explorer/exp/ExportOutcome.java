/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.explorer.exp;

import org.weasis.core.ui.util.SavedFile;

/** What an export really wrote. Filled by the export thread and read on the event thread. */
public final class ExportOutcome {

  public enum Status {
    COMPLETED,
    PARTIAL,
    FAILED,
    CANCELLED
  }

  private int written;
  private int failed;
  private String firstProblem;

  /** One file is complete at its destination. */
  public synchronized void saved() {
    written++;
  }

  /**
   * One file could not be saved.
   *
   * @param what a label for the file that carries no patient name, such as its instance identifier
   */
  public synchronized void failed(String what, Throwable error) {
    failed++;
    if (firstProblem == null) {
      firstProblem = what + ": " + reason(error);
    }
  }

  /** The files were written, but the one archive that had to hold them could not be. */
  public synchronized void lostAll(Throwable error) {
    failed += written;
    written = 0;
    firstProblem = reason(error);
  }

  public synchronized int written() {
    return written;
  }

  public synchronized int failed() {
    return failed;
  }

  public synchronized String firstProblem() {
    return firstProblem;
  }

  /**
   * @param cancelled the user stopped the export
   * @param thrown what ended the export early, or null when it ran through
   */
  public synchronized Status status(boolean cancelled, Throwable thrown) {
    if (cancelled) {
      return Status.CANCELLED;
    }
    if (written == 0) {
      return Status.FAILED;
    }
    return failed == 0 && thrown == null ? Status.COMPLETED : Status.PARTIAL;
  }

  /** The most useful text an exception has. */
  public static String reason(Throwable error) {
    return SavedFile.reason(error);
  }
}
