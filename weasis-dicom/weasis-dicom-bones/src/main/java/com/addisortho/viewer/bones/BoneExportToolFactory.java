/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones;

import java.util.Hashtable;
import java.util.Set;
import org.dcm4che3.data.Tag;
import org.osgi.service.component.annotations.Component;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.InsertableFactory;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.ui.docking.ExtToolFactory;
import org.weasis.core.ui.editor.image.ImageViewerEventManager;
import org.weasis.core.util.StringUtil;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.HiddenSeriesManager;
import org.weasis.dicom.codec.SpecialElementRegion;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.viewer3d.EventManager;

/** Adds the Bone STL tool to the 3D viewer when the shown series has a segmentation. */
@Component(
    service = InsertableFactory.class,
    property = {"org.weasis.dicom.viewer3d.View3DContainer=true"})
public class BoneExportToolFactory extends ExtToolFactory<DicomImageElement> {

  public BoneExportToolFactory() {
    super(BoneExportTool.BUTTON_NAME);
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof BoneExportTool;
  }

  @Override
  protected ImageViewerEventManager<DicomImageElement> getImageViewerEventManager() {
    return EventManager.getInstance();
  }

  /**
   * Same rule as the 3D Segmentation tool: a SEG references the series or its frame of reference.
   */
  @Override
  protected boolean isCompatible(Hashtable<String, Object> properties) {
    Object value = properties == null ? null : properties.get(MediaSeries.class.getName());
    if (!(value instanceof MediaSeries<?> series)) {
      return false;
    }
    String seriesUID = TagD.getTagValue(series, Tag.SeriesInstanceUID, String.class);
    if (!StringUtil.hasText(seriesUID)) {
      return false;
    }
    Set<String> referencing = HiddenSeriesManager.getInstance().reference2Series.get(seriesUID);
    if (referencing != null
        && !referencing.isEmpty()
        && HiddenSeriesManager.hasHiddenElementsFromSeries(
            SpecialElementRegion.class, referencing.toArray(new String[0]))) {
      return true;
    }
    return HiddenSeriesManager.hasHiddenElementsByFrameOfReference(
        SpecialElementRegion.class, series);
  }

  @Override
  protected Insertable getInstance(Hashtable<String, Object> properties) {
    return new BoneExportTool();
  }
}
