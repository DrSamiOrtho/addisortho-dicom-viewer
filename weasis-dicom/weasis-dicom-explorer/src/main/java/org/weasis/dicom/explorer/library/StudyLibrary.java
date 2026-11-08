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

import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JOptionPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.ui.util.SavedFile;
import org.weasis.dicom.explorer.Messages;

/**
 * The one managed store of this computer and its catalog, shared by import and receive, so that a
 * study stays available wherever it came from. Only one {@link StudyStore} exists per root.
 */
public final class StudyLibrary {
  private static final Logger LOGGER = LoggerFactory.getLogger(StudyLibrary.class);
  private static final StudyLibrary INSTANCE =
      new StudyLibrary(AppProperties.WEASIS_PATH.resolve("data").resolve("library")); // NON-NLS
  private static final String SETTINGS = "library.properties"; // NON-NLS
  private static final String KEY_ROOT = "root"; // NON-NLS
  private static final String KEY_NAME = "filename.include.name"; // NON-NLS
  private static final String CATALOG = ".catalog/studies.tsv"; // NON-NLS
  private static final long SAVE_DELAY_SECONDS = 2;

  private final Path settingsFolder;
  private final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "Study library"); // NON-NLS
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean savePending = new AtomicBoolean();
  // Guarded by this.
  private StudyStore store;
  private StudyCatalog catalog;

  StudyLibrary(Path settingsFolder) {
    this.settingsFolder = settingsFolder;
  }

  public static StudyLibrary getInstance() {
    return INSTANCE;
  }

  /** A plain folder of the home directory: Documents is often synchronised to a cloud service. */
  public static Path defaultRoot() {
    return Path.of(System.getProperty("user.home"), "AddisOrthoReceived"); // NON-NLS
  }

  /** The store, opened on the saved root the first time. */
  public synchronized StudyStore store() {
    if (store == null) {
      Properties p = loadSettings();
      String root = p.getProperty(KEY_ROOT, "");
      open(
          root.isBlank() ? defaultRoot() : Path.of(root),
          Boolean.parseBoolean(p.getProperty(KEY_NAME, "true")));
    }
    return store;
  }

  /**
   * Makes {@code root} the store of this computer and returns its one store. Studies in a previous
   * root stay where they are but leave the catalog: the catalog describes the current root.
   */
  public synchronized StudyStore useRoot(Path root, boolean includeName) {
    Path normal = root.toAbsolutePath().normalize();
    if (store == null || !store.root().equals(normal)) {
      open(normal, includeName);
    } else {
      store.setIncludeName(includeName);
    }
    saveSettings(normal, includeName);
    return store;
  }

  private void open(Path root, boolean includeName) {
    StudyStore opened = new StudyStore(root, includeName);
    StudyCatalog studies = new StudyCatalog(opened.root().resolve(CATALOG));
    studies.load();
    studies.onChanged(() -> changed(studies));
    opened.attachCatalog(studies);
    store = opened;
    catalog = studies;
    worker.execute(() -> reconcile(opened, studies));
  }

  private void reconcile(StudyStore opened, StudyCatalog studies) {
    try {
      StudyCatalog.Reconciled result = studies.reconcile(opened.root(), opened);
      LOGGER.info("Library reconciled: {} rebuilt, {} removed", result.rebuilt(), result.removed());
    } catch (IOException | RuntimeException e) {
      LOGGER.error("Cannot reconcile the study library: {}", e.getClass().getSimpleName());
    }
    fire();
  }

  private void changed(StudyCatalog studies) {
    fire();
    if (savePending.compareAndSet(false, true)) {
      worker.schedule(
          () -> {
            savePending.set(false);
            try {
              studies.save();
            } catch (IOException | RuntimeException e) {
              LOGGER.error("Cannot save the study catalog: {}", e.getClass().getSimpleName());
            }
          },
          SAVE_DELAY_SECONDS,
          TimeUnit.SECONDS);
    }
  }

  private void fire() {
    GuiExecutor.execute(() -> listeners.forEach(Runnable::run));
  }

  /** Called on the event dispatch thread whenever the catalog changes. */
  public void addListener(Runnable listener) {
    listeners.addIfAbsent(listener);
  }

  public synchronized List<CatalogStudy> studies() {
    store();
    return catalog.studies();
  }

  /** The folder of a catalog row. */
  public Path folderOf(CatalogStudy study) {
    return store().root().resolve(study.folder());
  }

  /**
   * Keeps a managed copy of the imported files in the background, then tells the user the result.
   * The viewer opens the originals meanwhile, so nothing waits for this.
   *
   * @param recursive also the subfolders of a folder
   */
  public void importAndReport(List<File> paths, boolean recursive, Component parent) {
    StudyStore target = store();
    worker.execute(
        () -> {
          LibraryImport.Report report;
          try {
            report = new LibraryImport(target, () -> false).run(paths, recursive);
          } catch (IOException | RuntimeException e) {
            LOGGER.error("Cannot import into the library: {}", e.getClass().getSimpleName());
            report = new LibraryImport.Report(0, 0, 0, 1, 0, SavedFile.reason(e), false);
          }
          saveNow(target);
          LibraryImport.Report done = report;
          GuiExecutor.execute(() -> show(done, target.root(), parent));
        });
  }

  private synchronized void saveNow(StudyStore target) {
    if (target == store) {
      try {
        catalog.save();
      } catch (IOException | RuntimeException e) {
        LOGGER.error("Cannot save the study catalog: {}", e.getClass().getSimpleName());
      }
    }
  }

  private static void show(LibraryImport.Report r, Path root, Component parent) {
    boolean bad = r.conflicts() > 0 || r.failed() > 0 || r.outOfSpace();
    String title = Messages.getString("library.import.title");
    if (r.stored() + r.alreadyHeld() + r.conflicts() + r.failed() == 0 && !r.outOfSpace()) {
      return; // no DICOM in the selection: the viewer's own loading already says so
    }
    StringBuilder text =
        new StringBuilder(
            MessageFormat.format(
                Messages.getString("library.import.saved"), r.stored(), root, r.alreadyHeld()));
    if (r.conflicts() > 0) {
      text.append("\n\n")
          .append(
              MessageFormat.format(Messages.getString("library.import.conflict"), r.conflicts()));
    }
    if (r.failed() > 0) {
      text.append("\n\n")
          .append(
              MessageFormat.format(
                  Messages.getString("library.import.failed"), r.failed(), r.firstProblem()));
    }
    if (r.outOfSpace()) {
      text.append("\n\n").append(Messages.getString("library.import.space"));
    }
    SavedFile.showSaved(
        parent,
        title,
        text.toString(),
        root.toFile(),
        bad ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
  }

  private Properties loadSettings() {
    Properties p = new Properties();
    Path file = settingsFolder.resolve(SETTINGS);
    if (Files.isRegularFile(file)) {
      try (InputStream in = Files.newInputStream(file)) {
        p.load(in);
      } catch (IOException | IllegalArgumentException e) {
        LOGGER.warn("Cannot read the library settings: {}", e.getClass().getSimpleName());
      }
    }
    return p;
  }

  private void saveSettings(Path root, boolean includeName) {
    Properties p = new Properties();
    p.setProperty(KEY_ROOT, root.toString());
    p.setProperty(KEY_NAME, Boolean.toString(includeName));
    try {
      Files.createDirectories(settingsFolder);
      try (OutputStream out = Files.newOutputStream(settingsFolder.resolve(SETTINGS))) {
        p.store(out, null);
      }
    } catch (IOException e) {
      LOGGER.error("Cannot save the library settings: {}", e.getClass().getSimpleName());
    }
  }
}
