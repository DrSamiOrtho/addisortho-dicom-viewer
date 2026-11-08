/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.ObservableEvent;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.TagW;
import org.weasis.core.ui.editor.SeriesViewerEvent;
import org.weasis.core.ui.editor.SeriesViewerEvent.EVENT;
import org.weasis.core.ui.editor.image.ViewCanvas;
import org.weasis.core.ui.editor.image.ViewerPlugin;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomMediaIO;
import org.weasis.dicom.codec.HiddenSeriesManager;
import org.weasis.dicom.codec.HiddenSpecialElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.codec.seg.SegSpecialElement;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.HangingProtocols.OpeningViewer;
import org.weasis.dicom.explorer.LoadLocalDicom;
import org.weasis.dicom.viewer2d.View2d;
import org.weasis.dicom.viewer2d.mpr.MprContainer;
import org.weasis.dicom.viewer2d.mpr.MprController;
import org.weasis.dicom.viewer2d.mpr.Volume;
import org.weasis.dicom.viewer3d.ActionVol;
import org.weasis.dicom.viewer3d.EventManager;
import org.weasis.dicom.viewer3d.View3DContainer;
import org.weasis.dicom.viewer3d.dockable.SegmentationTool;
import org.weasis.dicom.viewer3d.vr.View3d;

/**
 * Puts the region grow segmentation into the DICOM explorer, in the study of its CT: the previous
 * version of the same series is removed first, so the explorer holds one segmentation per session,
 * then the new file is imported like a local file. Once the explorer has read it, the 3D views of
 * the CT are refreshed. Used on the event dispatch thread.
 */
final class SegExplorer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SegExplorer.class);

  private DicomModel pendingModel;
  private PropertyChangeListener pending;

  /**
   * The replaced file stays in the viewer's temporary folder, as a view may still be reading it.
   *
   * @param oldSopUID the instance to replace, or {@code null}
   * @param newFile the new segmentation, or {@code null} to only remove the old one
   */
  void replace(
      MediaSeries<DicomImageElement> ct,
      String segSeriesUID,
      String oldSopUID,
      Path newFile,
      String newSopUID) {
    if (!(ct.getTagValue(TagW.ExplorerModel) instanceof DicomModel model)) {
      LOGGER.warn("The CT is not in the DICOM explorer: the segmentation is not loaded");
      return;
    }
    stopWaiting();
    if (oldSopUID != null) {
      remove(model, segSeriesUID, oldSopUID);
    }
    if (newFile == null) {
      // An earlier version may still be on its way into the explorer: once the loads queued
      // before now are done (one loading thread), take out whatever of the series is there.
      DicomModel.LOADING_EXECUTOR.execute(
          () -> GuiExecutor.execute(() -> removeAllBut(model, segSeriesUID, null)));
      return;
    }
    pendingModel = model;
    pending = evt -> loaded(evt, model, ct, segSeriesUID, newSopUID);
    model.addPropertyChangeListener(pending);
    DicomModel.LOADING_EXECUTOR.execute(
        new LoadLocalDicom(new File[] {newFile.toFile()}, false, model, OpeningViewer.NONE));
  }

  void stopWaiting() {
    if (pending != null) {
      pendingModel.removePropertyChangeListener(pending);
      pending = null;
      pendingModel = null;
    }
  }

  private static void remove(DicomModel model, String segSeriesUID, String sopUID) {
    Set<HiddenSpecialElement> elements =
        HiddenSeriesManager.getInstance().series2Elements.get(segSeriesUID);
    if (elements == null) {
      return;
    }
    for (HiddenSpecialElement element : List.copyOf(elements)) {
      if (element instanceof SegSpecialElement && sopUID.equals(sopUID(element))) {
        model.removeHiddenSpecialElement(element);
      }
    }
  }

  /**
   * Takes out of the explorer every instance of the series but {@code keepSopUID}: versions written
   * in quick succession may land after the removal meant for them.
   */
  private static void removeAllBut(DicomModel model, String segSeriesUID, String keepSopUID) {
    Set<HiddenSpecialElement> elements =
        HiddenSeriesManager.getInstance().series2Elements.get(segSeriesUID);
    if (elements == null) {
      return;
    }
    for (HiddenSpecialElement element : List.copyOf(elements)) {
      if (element instanceof SegSpecialElement && !Objects.equals(keepSopUID, sopUID(element))) {
        model.removeHiddenSpecialElement(element);
      }
    }
  }

  static String sopUID(HiddenSpecialElement element) {
    String uid = TagD.getTagValue(element, Tag.SOPInstanceUID, String.class);
    if (uid == null && element.getMediaReader() instanceof DicomMediaIO reader) {
      uid = reader.getDicomObject().getString(Tag.SOPInstanceUID);
    }
    return uid;
  }

  // The explorer announces a segmentation once its contours are built (UPDATE, off the EDT).
  private void loaded(
      PropertyChangeEvent evt,
      DicomModel model,
      MediaSeries<DicomImageElement> ct,
      String segSeriesUID,
      String sopInstanceUID) {
    if (evt instanceof ObservableEvent e
        && e.getActionCommand() == ObservableEvent.BasicAction.UPDATE
        && e.getNewValue() instanceof SegSpecialElement seg
        && Objects.equals(sopInstanceUID, sopUID(seg))) {
      GuiExecutor.execute(
          () -> {
            stopWaiting();
            removeAllBut(model, segSeriesUID, sopInstanceUID);
            refreshViews(ct);
          });
    }
  }

  /**
   * Shows the segmentations of the CT in its 3D views, its MPR views and the slices of the 2D
   * viewer, and lists them in the Segmentation panel.
   */
  private static void refreshViews(MediaSeries<DicomImageElement> ct) {
    List<ViewerPlugin<?>> plugins = GuiUtils.getUICore().getViewerPlugins();
    List<ViewerPlugin<?>> copy;
    synchronized (plugins) {
      copy = List.copyOf(plugins);
    }
    EventManager manager = EventManager.getInstance();
    String ctUID = TagD.getTagValue(ct, Tag.SeriesInstanceUID, String.class);
    for (ViewerPlugin<?> plugin : copy) {
      if (plugin instanceof MprContainer mpr) {
        // Live bones are drawn from memory: the file just loaded needs no rebuild of the MPR.
        if (org.weasis.dicom.codec.seg.LiveSegmentation.of(ctUID).isEmpty()) {
          refreshMpr(mpr, ctUID);
        }
        continue;
      }
      if (!(plugin instanceof View3DContainer container)) {
        refreshSlices(plugin, ctUID);
        continue;
      }
      for (ViewCanvas<DicomImageElement> canvas : container.getImagePanels()) {
        if (canvas instanceof View3d view
            && view.getVolTexture() != null
            && ct.equals(view.getVolTexture().getSeries())) {
          if (view.getSegType() == SegmentationTool.Type.NONE) {
            view.setSegType(BoneGrowTool.displayType());
          } else {
            view.updateSegmentation();
          }
        }
      }
      if (manager.getSelectedView2dContainer() == container) {
        manager
            .getAction(ActionVol.SEG_TYPE)
            .ifPresent(
                a ->
                    a.setSelectedItemWithoutTriggerAction(
                        manager.getSelectedViewPane() instanceof View3d v
                            ? v.getSegType()
                            : BoneGrowTool.displayType()));
      }
      manager.fireSeriesViewerListeners(
          new SeriesViewerEvent(container, ct, null, EVENT.SELECT_VIEW));
    }
  }

  /** The MPR of the CT builds its outlines again from the segmentation now in the explorer. */
  private static void refreshMpr(MprContainer mpr, String ctUID) {
    MprController controller = mpr.getMprController();
    Volume<?, ?> volume = controller == null ? null : controller.getVolume();
    if (volume == null || volume.getStack() == null) {
      return;
    }
    String uid =
        TagD.getTagValue(volume.getStack().getSeries(), Tag.SeriesInstanceUID, String.class);
    if (ctUID != null && ctUID.equals(uid)) {
      controller.buildSegElementsAsync(mpr);
    }
  }

  /** The slices of the CT in a 2D viewer draw their outlines again. */
  private static void refreshSlices(ViewerPlugin<?> plugin, String ctUID) {
    if (!(plugin instanceof org.weasis.core.ui.editor.image.ImageViewerPlugin<?> viewer)) {
      return;
    }
    for (ViewCanvas<?> canvas : viewer.getImagePanels()) {
      if (canvas instanceof View2d view
          && view.getSeries() != null
          && ctUID != null
          && ctUID.equals(
              TagD.getTagValue(view.getSeries(), Tag.SeriesInstanceUID, String.class))) {
        view.requestSegmentationUpdate();
      }
    }
  }

  /**
   * The live bones of the CT changed in slices {@code z0} to {@code z1} of its image volume ({@code
   * z0 > z1}: only their look): the MPR views draw them again, the 3D views upload those slices.
   */
  static void liveChanged(MediaSeries<DicomImageElement> ct, int z0, int z1) {
    String ctUID = TagD.getTagValue(ct, Tag.SeriesInstanceUID, String.class);
    if (ctUID == null) {
      return;
    }
    List<ViewerPlugin<?>> plugins = GuiUtils.getUICore().getViewerPlugins();
    List<ViewerPlugin<?>> copy;
    synchronized (plugins) {
      copy = List.copyOf(plugins);
    }
    for (ViewerPlugin<?> plugin : copy) {
      if (plugin instanceof MprContainer mpr) {
        MprController controller = mpr.getMprController();
        Volume<?, ?> volume = controller == null ? null : controller.getVolume();
        if (volume == null
            || volume.getStack() == null
            || !ctUID.equals(
                TagD.getTagValue(
                    volume.getStack().getSeries(), Tag.SeriesInstanceUID, String.class))) {
          continue;
        }
        for (org.weasis.dicom.viewer2d.mpr.MprView.Plane plane :
            org.weasis.dicom.viewer2d.mpr.MprView.Plane.values()) {
          org.weasis.dicom.viewer2d.mpr.MprView view = mpr.getMprView(plane);
          if (view != null) {
            view.updateSegmentation();
            view.repaint();
          }
        }
      } else if (plugin instanceof View3DContainer container) {
        for (ViewCanvas<DicomImageElement> canvas : container.getImagePanels()) {
          if (canvas instanceof View3d view
              && view.getVolTexture() != null
              && ct.equals(view.getVolTexture().getSeries())) {
            view.liveSegmentationChanged(z0, z1);
          }
        }
      }
    }
  }
}
