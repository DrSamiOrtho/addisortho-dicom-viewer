/*
 * Copyright (c) 2022 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
// Modified by Addis Ortho Trading PLC, 2026.
package org.weasis.launcher;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * The Addis Ortho dark theme. Its colours and shapes are in {@code
 * org/weasis/theme/FlatWeasisTheme.properties}, loaded through the custom defaults source
 * registered in {@link LookAndFeels}. The class name is kept because it is the stored {@code
 * weasis.theme} preference.
 */
public class FlatWeasisTheme extends FlatDarkLaf {
  public static boolean setup() {
    return setup(new FlatWeasisTheme());
  }

  @Override
  public String getName() {
    return "Addis Ortho"; // NON-NLS
  }
}
