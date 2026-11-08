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

import java.util.Set;

/**
 * One study held in the managed store, as the catalog knows it. No pixels and no file contents: a
 * row can always be rebuilt from the files in the study's folder.
 *
 * @param folder the study's folder name inside the store
 * @param imageDate the image's date as labelled by the naming policy, or null when unknown
 * @param firstStored when the study first reached this computer, ms; never the image's date
 * @param lastStored when the latest image of it did, ms
 */
public record CatalogStudy(
    String studyUid,
    String folder,
    String patientName,
    String patientId,
    String issuer,
    String birthDate,
    String sex,
    String studyDate,
    String imageDate,
    String studyDescription,
    String accession,
    Set<String> modalities,
    Set<String> series,
    int imageCount,
    long firstStored,
    long lastStored) {}
