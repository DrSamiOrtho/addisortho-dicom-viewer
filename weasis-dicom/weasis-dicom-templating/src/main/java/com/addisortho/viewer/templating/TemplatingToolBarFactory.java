/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.util.Hashtable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.weasis.core.api.gui.Insertable;
import org.weasis.core.api.gui.Insertable.Type;
import org.weasis.core.api.gui.InsertableFactory;

/** Adds the Template button to the 2D viewer; stops the templating server with the bundle. */
@Component(
    service = InsertableFactory.class,
    property = {"org.weasis.dicom.viewer2d.View2dContainer=true"})
public class TemplatingToolBarFactory implements InsertableFactory {

  @Override
  public Type getType() {
    return Type.TOOLBAR;
  }

  @Override
  public Insertable createInstance(Hashtable<String, Object> properties) {
    return new TemplatingToolBar();
  }

  @Override
  public boolean isComponentCreatedByThisFactory(Insertable component) {
    return component instanceof TemplatingToolBar;
  }

  @Override
  public void dispose(Insertable component) {
    // the server serves every viewer window; it stops with the bundle
  }

  @Deactivate
  protected void deactivate() {
    TemplatingService.stop();
  }
}
