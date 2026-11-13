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
import java.time.Clock;
import java.util.ArrayList;
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
import org.weasis.core.api.explorer.model.DataExplorerModel;
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
  private PictureLibrary pictures;

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
    pictures = new PictureLibrary(opened, Clock.systemUTC());
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

  /** The pictures kept apart from the DICOM studies, newest first. */
  public List<PictureLibrary.LocalPicture> pictures() {
    PictureLibrary held;
    synchronized (this) {
      store();
      held = pictures;
    }
    return held.list();
  }

  /** When the outcome of a copy interrupts the user. */
  private enum Notice {
    ALWAYS,
    ONLY_PROBLEMS
  }

  /**
   * Keeps a managed copy of what the user chose to import, in the background, then tells them the
   * result. The viewer opens the originals meanwhile, so nothing waits for this.
   *
   * @param recursive also the subfolders of a folder
   */
  public void importAndReport(
      List<File> paths, boolean recursive, Component parent, DataExplorerModel model) {
    retain(paths, recursive, parent, model, Notice.ALWAYS, true, false, 0, null);
  }

  /** Keeps the copy made of a disc. What else lies on a disc is its own, so no picture is asked. */
  public void retainDisc(File copy, DataExplorerModel model, int lost, String firstLost) {
    retain(List.of(copy), true, null, model, Notice.ALWAYS, false, false, lost, firstLost);
  }

  /** Keeps the images a DICOMDIR refers to, and nothing else that lies beside it. */
  public void retainReferenced(
      List<File> files, DataExplorerModel model, int unreadPart, String unreadName) {
    retain(files, false, null, model, Notice.ALWAYS, false, true, unreadPart, unreadName);
  }

  /**
   * Keeps a managed copy of files dropped on the application. Dropping is how studies are read, so
   * a copy that went well does not interrupt: only a problem does, and the study is in the library.
   */
  public void retainDropped(
      List<File> paths, boolean recursive, Component parent, DataExplorerModel model) {
    retain(paths, recursive, parent, model, Notice.ONLY_PROBLEMS, true, false, 0, null);
  }

  private void retain(
      List<File> paths,
      boolean recursive,
      Component parent,
      DataExplorerModel model,
      Notice notice,
      boolean askAboutPictures,
      boolean everyFileIsDicom,
      int lost,
      String firstLost) {
    StudyStore target = store();
    new RetainTask(
            model,
            worker,
            (cancelled, progress) -> {
              LibraryImport.Report report;
              try {
                report =
                    new LibraryImport(target, cancelled, ArchiveGuard.DEFAULT, progress)
                        .expectingOnlyDicom(everyFileIsDicom)
                        .withFilesLostBefore(lost, firstLost)
                        .run(paths, recursive);
              } catch (IOException | RuntimeException e) {
                LOGGER.error("Cannot import into the library: {}", e.getClass().getSimpleName());
                report = LibraryImport.Report.failure(SavedFile.reason(e));
              }
              saveNow(target);
              LibraryImport.Report done = report;
              GuiExecutor.execute(() -> afterCopy(done, target, parent, notice, askAboutPictures));
            })
        .execute();
  }

  /** On the event dispatch thread: the only step that waits for the user. */
  private void afterCopy(
      LibraryImport.Report report,
      StudyStore target,
      Component parent,
      Notice notice,
      boolean askAboutPictures) {
    List<Path> found = askAboutPictures ? report.pictures() : List.of();
    if (found.isEmpty()) {
      show(report, PictureLibrary.Result.NONE, target.root(), parent, notice);
      return;
    }
    boolean onlyOnePicture = found.size() == 1 && report.dicomObjects() == 0;
    List<PictureConfirmDialog.Assignment> chosen =
        PictureConfirmDialog.review(parent, found, onlyOnePicture);
    if (chosen.isEmpty()) {
      show(report, PictureLibrary.Result.declined(found.size()), target.root(), parent, notice);
      return;
    }
    PictureLibrary held = pictureLibraryOf(target);
    worker.execute(
        () -> {
          PictureLibrary.Result all = PictureLibrary.Result.NONE;
          for (PictureConfirmDialog.Assignment one : chosen) {
            all = all.plus(held.keepAll(one.pictures(), one.identity()));
          }
          PictureLibrary.Result result = all;
          fire();
          GuiExecutor.execute(() -> show(report, result, target.root(), parent, Notice.ALWAYS));
        });
  }

  private synchronized PictureLibrary pictureLibraryOf(StudyStore target) {
    return target == store ? pictures : new PictureLibrary(target, Clock.systemUTC());
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

  private static void show(
      LibraryImport.Report r, PictureLibrary.Result p, Path root, Component parent, Notice notice) {
    boolean bad =
        r.conflicts() > 0
            || r.failed() > 0
            || r.outOfSpace()
            || r.archivesRefused() > 0
            || r.nestedArchives() > 0
            || p.conflicts() > 0
            || p.failed() > 0
            || p.outOfSpace();
    boolean anything =
        r.stored() + r.alreadyHeld() > 0 || r.cancelled() || p.kept() + p.alreadyHeld() > 0;
    if (!bad && (!anything || notice == Notice.ONLY_PROBLEMS && !r.cancelled())) {
      return; // no DICOM in the selection: the viewer's own loading already says so
    }
    SavedFile.showSaved(
        parent,
        Messages.getString("library.import.title"),
        message(r, p, root),
        root.toFile(),
        bad ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
  }

  private static String message(LibraryImport.Report r, PictureLibrary.Result p, Path root) {
    List<String> parts = new ArrayList<>();
    if (r.stored() + r.alreadyHeld() + r.conflicts() + r.failed() > 0 || r.cancelled()) {
      parts.add(format("library.import.saved", r.stored(), root, r.alreadyHeld()));
    }
    if (r.conflicts() > 0) {
      parts.add(format("library.import.conflict", r.conflicts()));
    }
    if (r.failed() > 0) {
      parts.add(format("library.import.failed", r.failed(), r.firstProblem()));
    }
    if (r.archivesRefused() > 0) {
      parts.add(format("library.import.archive", r.archivesRefused(), r.firstProblem()));
    }
    if (r.nestedArchives() > 0) {
      parts.add(format("library.import.nested", r.nestedArchives()));
    }
    if (r.outOfSpace() || p.outOfSpace()) {
      parts.add(Messages.getString("library.import.space"));
    }
    if (r.cancelled()) {
      parts.add(Messages.getString("library.import.cancelled"));
    }
    if (p.kept() + p.alreadyHeld() > 0) {
      parts.add(format("library.picture.kept", p.kept(), p.alreadyHeld()));
    }
    if (p.conflicts() > 0) {
      parts.add(format("library.picture.conflict", p.conflicts()));
    }
    if (p.failed() > 0) {
      parts.add(format("library.picture.failed", p.failed(), p.firstProblem()));
    }
    return String.join("\n\n", parts);
  }

  private static String format(String key, Object... values) {
    return MessageFormat.format(Messages.getString(key), values);
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
