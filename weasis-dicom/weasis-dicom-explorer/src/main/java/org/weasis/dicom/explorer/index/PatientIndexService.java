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
package org.weasis.dicom.explorer.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.gui.util.AppProperties;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.Messages;

/**
 * The patients on this computer: the folders to search, their index, kept up to date by one
 * background worker, and the one-off searches of other folders.
 */
public final class PatientIndexService {
  private static final Logger LOGGER = LoggerFactory.getLogger(PatientIndexService.class);

  private static final PatientIndexService INSTANCE =
      new PatientIndexService(
          new IndexStore(
              AppProperties.WEASIS_PATH.resolve("data").resolve("patient-search"))); // NON-NLS

  /** What a scan reports. */
  public record Status(boolean running, int filesSeen, int dicomFound, String folder) {}

  private final IndexStore store;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "Patient search index"); // NON-NLS
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
          });
  private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
  private final AtomicBoolean cancel = new AtomicBoolean();
  private volatile List<IndexedFile> files;
  private volatile List<StudyEntry> studies = List.of();
  private volatile List<String> notIndexed = List.of();
  private volatile Status status = new Status(false, 0, 0, null);
  private volatile boolean startupScanDone;

  PatientIndexService(IndexStore store) {
    this.store = store;
  }

  public static PatientIndexService getInstance() {
    return INSTANCE;
  }

  /** Loads the saved index and, once per session, refreshes the folders in the user's home. */
  public synchronized void start() {
    if (files == null) {
      files = store.loadIndex();
      studies = StudyEntry.of(files, false);
    }
    if (!startupScanDone) {
      startupScanDone = true;
      // Network and removable drives are left to Rescan; the home folders are quick.
      Path home = Path.of(System.getProperty("user.home"));
      List<Path> local = folders().stream().filter(p -> p.startsWith(home)).toList();
      if (!local.isEmpty()) {
        rescan(local);
      }
    }
  }

  public List<Path> folders() {
    return store.loadFolders();
  }

  public void setFolders(List<Path> folders) {
    try {
      store.saveFolders(List.copyOf(new LinkedHashSet<>(folders)));
    } catch (IOException e) {
      LOGGER.error("Cannot save the search folders", e);
    }
  }

  /**
   * Folders worth offering when they exist: where Telegram Desktop saves files (Mac and Windows),
   * Downloads (the Mac App Store Telegram and browsers) and Desktop.
   */
  public static List<Path> suggestedFolders() {
    Path home = Path.of(System.getProperty("user.home"));
    List<Path> candidates =
        List.of(
            home.resolve("Downloads").resolve("Telegram Desktop"), // NON-NLS
            home.resolve("Documents").resolve("Telegram Desktop"), // NON-NLS
            home.resolve("Downloads"), // NON-NLS
            home.resolve("Desktop")); // NON-NLS
    return candidates.stream().filter(Files::isDirectory).toList();
  }

  /** Whether a folder is too wide or a system one to index. */
  public static String refusal(Path folder) {
    Path home = Path.of(System.getProperty("user.home"));
    if (folder.getParent() == null || folder.equals(home)) {
      return Messages.getString("index.refuse.wide");
    }
    String s = folder.toString().toLowerCase(Locale.ROOT);
    if (s.contains("/library")
        || s.contains("\\appdata")
        || s.contains("program files") // NON-NLS
        || s.startsWith("/system")
        || s.startsWith("/applications") // NON-NLS
        || s.contains("\\windows")) { // NON-NLS
      return Messages.getString("index.refuse.system");
    }
    return null;
  }

  public List<StudyEntry> studies() {
    return studies;
  }

  public List<String> notIndexed() {
    return notIndexed;
  }

  public Status status() {
    return status;
  }

  /** Called on the event dispatch thread whenever the index or the scan status changes. */
  public void addListener(Runnable listener) {
    listeners.add(listener);
  }

  public void removeListener(Runnable listener) {
    listeners.remove(listener);
  }

  private void fire() {
    GuiExecutor.execute(() -> listeners.forEach(Runnable::run));
  }

  /** Rescans all the saved folders. */
  public void rescanAll() {
    rescan(folders());
  }

  private void rescan(List<Path> roots) {
    cancel.set(false);
    worker.execute(
        () -> {
          status = new Status(true, 0, 0, null);
          fire();
          try {
            List<IndexedFile> before = files == null ? List.of() : files;
            IndexScanner scanner =
                new IndexScanner(before, System.currentTimeMillis(), cancel::get, this::progress);
            IndexScanner.Result r = scanner.scan(roots);
            if (!cancel.get()) {
              // Folders not scanned this time (e.g. a drive left to Rescan) keep their entries.
              List<IndexedFile> kept = new ArrayList<>(r.files());
              for (IndexedFile f : before) {
                Path container = Path.of(f.container());
                if (roots.stream().noneMatch(container::startsWith)
                    && folders().stream().anyMatch(container::startsWith)) {
                  kept.add(f);
                }
              }
              files = kept;
              studies = StudyEntry.of(kept, false);
              notIndexed = r.notIndexed();
              store.saveIndex(kept);
            }
          } catch (IOException | RuntimeException e) {
            LOGGER.error("Patient search index", e);
          } finally {
            status = new Status(false, 0, 0, null);
            fire();
          }
        });
  }

  private void progress(int seen, int dicom, Path folder) {
    if (seen % 200 == 0) {
      status = new Status(true, seen, dicom, folder == null ? null : folder.toString());
      fire();
    }
  }

  /** Stops the running scan; what was indexed before stays. */
  public void cancel() {
    cancel.set(true);
  }

  /** Forgets the index; the next Rescan builds it again. */
  public void clear() {
    cancel();
    worker.execute(
        () -> {
          try {
            store.clearIndex();
          } catch (IOException e) {
            LOGGER.error("Cannot clear the patient search index", e);
          }
          files = new ArrayList<>();
          studies = List.of();
          fire();
        });
  }

  /** Scans a folder once, without saving it; the studies found go to {@code result}. */
  public void scanOnce(Path folder, Consumer<List<StudyEntry>> result) {
    cancel.set(false);
    worker.execute(
        () -> {
          status = new Status(true, 0, 0, folder.toString());
          fire();
          List<StudyEntry> found = List.of();
          try {
            IndexScanner scanner =
                new IndexScanner(List.of(), Long.MAX_VALUE / 2, cancel::get, this::progress);
            found = StudyEntry.of(scanner.scan(List.of(folder)).files(), true);
          } catch (IOException | RuntimeException e) {
            LOGGER.error("Search of {}", folder, e);
          } finally {
            status = new Status(false, 0, 0, null);
            List<StudyEntry> done = found;
            GuiExecutor.execute(() -> result.accept(done));
            fire();
          }
        });
  }

  /** Opens a study in the viewer: its files, or the archives that hold it. */
  public static void open(StudyEntry study, DicomModel model) {
    Set<File> files = new LinkedHashSet<>();
    for (String c : study.containers()) {
      File f = new File(c);
      if (f.canRead()) {
        files.add(f);
      }
    }
    if (!files.isEmpty()) {
      DicomModel.LOADING_EXECUTOR.execute(
          new LoadLocalDicom(files.toArray(new File[0]), false, model, OpeningViewer.ALL_PATIENTS));
    }
  }
}
