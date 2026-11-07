/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.bones.grow;

/** A growable first-in first-out queue of ints, without boxing. */
final class IntQueue {

  private int[] items;
  private int head;
  private int size;

  IntQueue(int capacity) {
    items = new int[Math.max(16, capacity)];
  }

  boolean isEmpty() {
    return size == 0;
  }

  void add(int value) {
    if (size == items.length) {
      int[] grown = new int[Math.toIntExact(Math.min(Integer.MAX_VALUE - 8L, 2L * items.length))];
      int tail = items.length - head;
      System.arraycopy(items, head, grown, 0, tail);
      System.arraycopy(items, 0, grown, tail, head);
      items = grown;
      head = 0;
    }
    items[(head + size) % items.length] = value;
    size++;
  }

  int poll() {
    int value = items[head];
    head = (head + 1) % items.length;
    size--;
    return value;
  }

  void clear() {
    head = 0;
    size = 0;
  }
}
