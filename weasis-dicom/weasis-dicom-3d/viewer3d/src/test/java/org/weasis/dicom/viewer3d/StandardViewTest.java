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
package org.weasis.dicom.viewer3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.viewer3d.geometry.CameraView;

class StandardViewTest {

  /** RadiAnt's 3D keys: 1 anterior, 2 posterior, 3 left, 4 right, 5 superior, 6 inferior. */
  @Test
  void digitsFollowRadiAnt() {
    List<StandardView> expected =
        List.of(
            StandardView.ANTERIOR,
            StandardView.POSTERIOR,
            StandardView.LEFT,
            StandardView.RIGHT,
            StandardView.SUPERIOR,
            StandardView.INFERIOR);
    for (int d = 1; d <= 6; d++) {
      StandardView view = expected.get(d - 1);
      assertEquals(view, StandardView.ofKey(KeyEvent.VK_0 + d).orElseThrow());
      assertEquals(view, StandardView.ofKey(KeyEvent.VK_NUMPAD0 + d).orElseThrow());
      assertEquals(d, view.digit());
    }
    assertEquals(CameraView.FRONT, StandardView.ANTERIOR.cameraView());
    assertEquals(CameraView.BACK, StandardView.POSTERIOR.cameraView());
    assertEquals(CameraView.LEFT, StandardView.LEFT.cameraView());
    assertEquals(CameraView.RIGHT, StandardView.RIGHT.cameraView());
    assertEquals(CameraView.TOP, StandardView.SUPERIOR.cameraView());
    assertEquals(CameraView.BOTTOM, StandardView.INFERIOR.cameraView());
  }

  @Test
  void otherKeysAreNotViews() {
    for (int key :
        new int[] {
          KeyEvent.VK_0, KeyEvent.VK_7, KeyEvent.VK_NUMPAD0, KeyEvent.VK_NUMPAD7, KeyEvent.VK_A
        }) {
      assertTrue(StandardView.ofKey(key).isEmpty(), "key " + key);
    }
  }

  /** The direction from the volume towards the viewer, in volume space. */
  private static Vector3d towardViewer(StandardView view) {
    Quaterniond r = view.cameraView().rotation();
    return new Quaterniond(r).conjugate().transform(new Vector3d(0, 0, 1));
  }

  @Test
  void oppositeViewsLookFromOppositeSidesAndPairsAreSquare() {
    assertOpposite(StandardView.ANTERIOR, StandardView.POSTERIOR);
    assertOpposite(StandardView.LEFT, StandardView.RIGHT);
    assertOpposite(StandardView.SUPERIOR, StandardView.INFERIOR);
    assertSquare(StandardView.ANTERIOR, StandardView.LEFT);
    assertSquare(StandardView.ANTERIOR, StandardView.SUPERIOR);
    assertSquare(StandardView.LEFT, StandardView.SUPERIOR);
  }

  private static void assertOpposite(StandardView a, StandardView b) {
    assertEquals(-1, towardViewer(a).dot(towardViewer(b)), 1e-6, a + " / " + b);
  }

  private static void assertSquare(StandardView a, StandardView b) {
    assertEquals(0, towardViewer(a).dot(towardViewer(b)), 1e-6, a + " / " + b);
  }
}
