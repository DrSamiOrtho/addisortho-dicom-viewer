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
package org.weasis.dicom.explorer.imp;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.DiscDrive;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadDicomDir;
import org.weasis.dicom.explorer.LocalPersistence;
import org.weasis.dicom.explorer.wado.LoadSeries;

/**
 * Opens a CD or DVD as soon as it is inserted, as Horos does: every few seconds it looks for a
 * read-only volume with a DICOMDIR that was not there before and loads it, copying the images into
 * the local cache so the disc can be taken out. Discs already in the drive when the viewer starts
 * are left alone ("Detect CD-ROM" in the import dialog still opens them). Switched off by the "Open
 * a CD/DVD as soon as it is inserted" box of the DICOMDIR import page.
 */
public final class DiscWatcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(DiscWatcher.class);

  private static final String AUTO_IMPORT = "cd.auto.import"; // NON-NLS
  private static final long PERIOD_SECONDS = 3;

  private static ScheduledExecutorService executor;

  /** The discs seen at the last look, as path, size and date of their DICOMDIR. */
  private static final Set<String> seen = new HashSet<>();

  private DiscWatcher() {}

  public static boolean isEnabled() {
    return LocalPersistence.getProperties().getBooleanProperty(AUTO_IMPORT, true);
  }

  public static void setEnabled(boolean enabled) {
    LocalPersistence.getProperties().putBooleanProperty(AUTO_IMPORT, enabled);
  }

  public static synchronized void start(DicomModel dicomModel) {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "CD/DVD watcher"); // NON-NLS
              t.setDaemon(true);
              t.setPriority(Thread.MIN_PRIORITY);
              return t;
            });
    // The first look only records the discs already there.
    executor.execute(() -> look(dicomModel, false));
    executor.scheduleWithFixedDelay(
        () -> look(dicomModel, true), PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
  }

  public static synchronized void stop() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  private static void look(DicomModel dicomModel, boolean open) {
    try {
      Set<String> now = new HashSet<>();
      for (File root : DiscDrive.mountedRoots()) {
        File dicomDir = DiscDrive.findReadOnlyDicomDir(root);
        if (dicomDir == null) {
          continue;
        }
        String key = dicomDir.getPath() + '|' + dicomDir.length() + '|' + dicomDir.lastModified();
        now.add(key);
        if (open && !seen.contains(key) && isEnabled()) {
          LOGGER.info("CD/DVD inserted, opening {}", dicomDir);
          List<LoadSeries> loadSeries = DicomDirImport.loadDicomDir(dicomDir, dicomModel, true);
          if (loadSeries != null && !loadSeries.isEmpty()) {
            DicomModel.LOADING_EXECUTOR.execute(
                new LoadDicomDir(loadSeries, dicomModel, OpeningViewer.ALL_PATIENTS));
          }
        }
      }
      seen.clear();
      seen.addAll(now);
    } catch (Exception e) {
      LOGGER.error("Cannot look for a CD/DVD", e);
    }
  }
}
