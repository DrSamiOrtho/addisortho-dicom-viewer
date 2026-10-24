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
import org.dcm4che3.data.Tag;
import org.osgi.service.component.annotations.Component;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.InsertableFactory;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.ui.docking.ExtToolFactory;
import org.weasis.core.ui.editor.image.ImageViewerEventManager;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.viewer3d.EventManager;

/**
 * Adds the Bones panel to the 3D viewer and to the 2D viewer, whose tools the MPR shares, when they
 * show a CT. The panels share one state; see {@link BoneGrowTool}.
 */
@Component(
    service = InsertableFactory.class,
    property = {
      "org.weasis.dicom.viewer3d.View3DContainer=true",
      "org.weasis.dicom.viewer2d.View2dContainer=true"
    })
public class BoneGrowToolFactory extends ExtToolFactory<DicomImageElement> {

  public BoneGrowToolFactory() {
    super(BoneGrowTool.BUTTON_NAME);
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof BoneGrowTool;
  }

  @Override
  protected ImageViewerEventManager<DicomImageElement> getImageViewerEventManager() {
    return EventManager.getInstance();
  }

  @Override
  protected boolean isCompatible(Hashtable<String, Object> properties) {
    Object value = properties == null ? null : properties.get(MediaSeries.class.getName());
    return value instanceof MediaSeries<?> series
        && "CT".equals(TagD.getTagValue(series, Tag.Modality, String.class)); // NON-NLS
  }

  @Override
  protected Insertable getInstance(Hashtable<String, Object> properties) {
    return new BoneGrowTool();
  }
}
