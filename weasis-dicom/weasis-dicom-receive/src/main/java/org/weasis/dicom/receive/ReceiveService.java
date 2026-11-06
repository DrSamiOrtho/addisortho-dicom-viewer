/*
 * Copyright (c) 2009-2020 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.receive;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.media.data.MediaSeriesGroupNode;
import org.weasis.core.api.service.BundlePreferences;
import org.weasis.core.ui.editor.SeriesViewerFactory;
import org.weasis.core.ui.editor.TabFocusPolicy;
import org.weasis.core.ui.editor.ViewerOpenOptions;
import org.weasis.core.ui.editor.ViewerPlacement;
import org.weasis.core.ui.editor.ViewerPluginBuilder;
import org.weasis.core.util.PropertiesUtil;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.TagD.Level;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.explorer.PluginOpeningStrategy;
import org.weasis.dicom.explorer.main.DicomExplorer;
import org.weasis.dicom.explorer.main.StudyPane;

/**
 * Runs the receiver in the viewer: starts it from the settings, adds what arrives to the explorer
 * without opening or closing any viewer, and tells the user.
 */
@Component(immediate = true, service = ReceiveService.class)
public class ReceiveService implements ReceiveListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReceiveService.class);
  private static final String SETTINGS_FILE = "receive.properties";
  private static final long BATCH_INTERVAL_MS = 1000;
  private static final long PROBLEM_REPEAT_MS = 60_000;

  private static volatile ReceiveService instance;

  private final Object batchLock = new Object();
  private final Map<String, List<File>> pendingByStudy = new LinkedHashMap<>();
  private final Map<String, Integer> receivedByStudy = new LinkedHashMap<>();
  private final Map<ReceiveProblem, Long> lastProblemTime = new EnumMap<>(ReceiveProblem.class);

  private ScheduledExecutorService batchTimer;
  private ExecutorService lifecycle;
  private Path settingsFile;
  private volatile ReceiveSettings settings = ReceiveSettings.defaults();
  private volatile DicomReceiver receiver;
  private volatile String startError;

  public static Optional<ReceiveService> get() {
    return Optional.ofNullable(instance);
  }

  @Activate
  protected void activate(ComponentContext context) {
    settingsFile = BundlePreferences.getFileInDataFolder(context.getBundleContext(), SETTINGS_FILE);
    Properties properties = new Properties();
    if (Files.isReadable(settingsFile)) {
      PropertiesUtil.loadProperties(settingsFile, properties);
    }
    settings = ReceiveSettings.fromProperties(properties);
    batchTimer = Executors.newSingleThreadScheduledExecutor(daemon("dicom-receive-batch"));
    lifecycle = Executors.newSingleThreadExecutor(daemon("dicom-receive-lifecycle"));
    batchTimer.scheduleWithFixedDelay(
        this::loadPending, BATCH_INTERVAL_MS, BATCH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    instance = this;
    lifecycle.execute(this::restart);
  }

  @Deactivate
  protected void deactivate() {
    instance = null;
    stopReceiver();
    batchTimer.shutdownNow();
    lifecycle.shutdownNow();
  }

  private static java.util.concurrent.ThreadFactory daemon(String name) {
    return Thread.ofPlatform().name(name).daemon().factory();
  }

  public ReceiveSettings settings() {
    return settings;
  }

  /** Saves the settings and restarts the receiver in the background. */
  public void apply(ReceiveSettings newSettings, Runnable whenDone) {
    settings = newSettings;
    PropertiesUtil.storeProperties(settingsFile, newSettings.toProperties(), null);
    lifecycle.execute(
        () -> {
          restart();
          GuiExecutor.execute(whenDone);
        });
  }

  public boolean isRunning() {
    DicomReceiver r = receiver;
    return r != null && r.isRunning();
  }

  /** The key of the plain message of the last failed start. */
  public Optional<String> startError() {
    return Optional.ofNullable(startError);
  }

  /** A store of the current storage folder, also when the receiver is off. */
  public ReceivedStudyStore store() {
    DicomReceiver r = receiver;
    return r != null
        ? r.store()
        : new ReceivedStudyStore(settings.storageFolder(), settings.includeNameInFileNames());
  }

  private void restart() {
    stopReceiver();
    startError = null;
    if (!settings.enabled()) {
      return;
    }
    DicomReceiver newReceiver = new DicomReceiver(settings, this);
    try {
      newReceiver.start();
      receiver = newReceiver;
    } catch (ReceiveStartException e) {
      startError = e.messageKey();
      ReceiveToast.showMessage(Messages.getString(e.messageKey()));
    }
  }

  private void stopReceiver() {
    DicomReceiver r = receiver;
    receiver = null;
    if (r != null) {
      r.stop();
    }
  }

  @Override
  public void onStored(ReceivedInstance instance) {
    synchronized (batchLock) {
      pendingByStudy
          .computeIfAbsent(instance.studyUid(), k -> new ArrayList<>())
          .add(instance.file().toFile());
    }
  }

  @Override
  public void onProblem(ReceiveProblem problem, String detail) {
    long now = System.currentTimeMillis();
    synchronized (lastProblemTime) {
      Long last = lastProblemTime.get(problem);
      if (last != null && now - last < PROBLEM_REPEAT_MS) {
        return;
      }
      lastProblemTime.put(problem, now);
    }
    ReceiveToast.showMessage(Messages.getString(problem.messageKey()).formatted(detail));
  }

  private Optional<DicomExplorer> explorer() {
    DataExplorerView view = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
    return view instanceof DicomExplorer explorer ? Optional.of(explorer) : Optional.empty();
  }

  private void loadPending() {
    Optional<DicomExplorer> explorer = explorer();
    if (explorer.isEmpty()) {
      return; // The window is still starting: what arrived stays pending.
    }
    Map<String, List<File>> batch;
    synchronized (batchLock) {
      if (pendingByStudy.isEmpty()) {
        return;
      }
      batch = new LinkedHashMap<>(pendingByStudy);
      pendingByStudy.clear();
    }
    File[] files = batch.values().stream().flatMap(List::stream).toArray(File[]::new);
    LOGGER.info("Received {} images in {} studies", files.length, batch.size());
    DicomExplorer view = explorer.get();
    DicomModel.LOADING_EXECUTOR.execute(
        () -> {
          load(files, view.getDataExplorerModel());
          batch.forEach((study, list) -> countArrival(view, study, list.size()));
        });
  }

  /**
   * No viewer may open or close for images that just arrived. A partial session also skips the
   * post-processing of whole series, which would misjudge a series that is still arriving, and the
   * dialogs that would interrupt the doctor.
   */
  private static void load(File[] files, DicomModel model) {
    PluginOpeningStrategy strategy = new PluginOpeningStrategy(OpeningViewer.NONE, false);
    strategy.setFullImportSession(false);
    new LoadLocalDicom(files, false, model, strategy).run();
  }

  private void countArrival(DicomExplorer view, String studyUid, int count) {
    int total;
    synchronized (receivedByStudy) {
      total = receivedByStudy.merge(studyUid, count, Integer::sum);
    }
    DicomModel model = view.getDataExplorerModel();
    MediaSeriesGroup study = model.getStudyNode(studyUid);
    if (study == null) {
      return; // The viewer could not read these images: the files are still in the folder.
    }
    MediaSeriesGroup patient = model.getParent(study, DicomModel.patient);
    GuiExecutor.execute(
        () -> {
          study.setTag(StudyPane.RECEIVED_IMAGES, total);
          StudyPane pane = view.getPaneManager().getStudyPane(study);
          if (pane != null) {
            pane.updateText();
          }
          ReceiveToast.showArrival(
              Messages.getString("receive.toast.received").formatted(total, patient),
              () -> show(view, study));
        });
  }

  /** Shows a study on request, in a new tab, leaving the open viewers as they are. */
  private static void show(DicomExplorer view, MediaSeriesGroup study) {
    DicomModel model = view.getDataExplorerModel();
    MediaSeriesGroup patient = model.getParent(study, DicomModel.patient);
    if (patient == null) {
      return; // The study was closed in the meantime.
    }
    view.selectPatient(patient);
    for (MediaSeriesGroup item : model.getChildren(study)) {
      if (item instanceof DicomSeries series && !DicomModel.isHiddenModality(series)) {
        SeriesViewerFactory factory = GuiUtils.getUICore().getViewerFactory(series.getMimeType());
        if (factory != null) {
          ViewerOpenOptions options =
              ViewerOpenOptions.builder()
                  .placement(ViewerPlacement.newTab())
                  .tabFocusPolicy(TabFocusPolicy.foreground())
                  .build();
          new ViewerPluginBuilder(factory, List.of(series), model, options).open();
          return;
        }
      }
    }
  }

  /** Loads a kept study from the storage folder, for example after a restart, and shows it. */
  public void showStudyFolder(ReceivedStudy received) {
    Optional<DicomExplorer> explorer = explorer();
    if (explorer.isEmpty()) {
      return;
    }
    DicomExplorer view = explorer.get();
    DicomModel model = view.getDataExplorerModel();
    DicomModel.LOADING_EXECUTOR.execute(
        () -> {
          load(new File[] {received.folder().toFile()}, model);
          findStudy(model, received.studyKey())
              .ifPresent(study -> GuiExecutor.execute(() -> show(view, study)));
        });
  }

  private static Optional<MediaSeriesGroup> findStudy(DicomModel model, String studyKey) {
    for (MediaSeriesGroup patient : model.getChildren(MediaSeriesGroupNode.rootNode)) {
      for (MediaSeriesGroup study : model.getChildren(patient)) {
        Object uid = study.getTagValue(TagD.getUID(Level.STUDY));
        if (uid instanceof String text && studyKey.equals(ReceiveNaming.studyKey(text))) {
          return Optional.of(study);
        }
      }
    }
    return Optional.empty();
  }

  /** Closes the study in the viewer, which releases its files, then deletes its folder. */
  public void deleteStudy(ReceivedStudy received) throws IOException {
    explorer()
        .ifPresent(
            view -> {
              DicomModel model = view.getDataExplorerModel();
              findStudy(model, received.studyKey()).ifPresent(model::removeStudy);
            });
    store().deleteStudy(received.folder());
  }
}
