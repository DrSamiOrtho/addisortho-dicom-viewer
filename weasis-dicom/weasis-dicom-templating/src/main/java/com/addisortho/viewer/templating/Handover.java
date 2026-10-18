/*
 * Copyright (c) 2026 Addis Ortho Trading PLC.
 *
 * This program and the accompanying materials are made available under the terms of the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.addisortho.viewer.templating;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;

/** One X-ray handed over to the templating page, and the plans the page saved for it. */
public final class Handover {

  /** A plan the viewer holds for this X-ray. */
  public record Plan(String sop, String series, Path file) {}

  private final String token;
  private final Path xray;
  private final Instant created;
  private final Object owner;
  private final String sopClass;
  private final String sop;
  private final String study;
  private final String series;
  private final String modality;
  private final String seriesDescription;
  private final String instanceNumber;
  private final String patientId;
  private final String issuer;
  private final Map<String, Plan> plans = new ConcurrentHashMap<>();

  Handover(String token, Path xray, Attributes header, Instant created, Object owner) {
    this.token = Objects.requireNonNull(token);
    this.xray = Objects.requireNonNull(xray);
    this.created = Objects.requireNonNull(created);
    this.owner = owner;
    this.sopClass = text(header, Tag.SOPClassUID);
    this.sop = text(header, Tag.SOPInstanceUID);
    this.study = text(header, Tag.StudyInstanceUID);
    this.series = text(header, Tag.SeriesInstanceUID);
    this.modality = text(header, Tag.Modality);
    this.seriesDescription = text(header, Tag.SeriesDescription);
    this.instanceNumber = text(header, Tag.InstanceNumber);
    this.patientId = text(header, Tag.PatientID);
    this.issuer = text(header, Tag.IssuerOfPatientID);
  }

  static String text(Attributes a, int tag) {
    String v = a.getString(tag);
    return v == null ? "" : v;
  }

  public String token() {
    return token;
  }

  public Path xray() {
    return xray;
  }

  public Instant created() {
    return created;
  }

  /** What the caller attached to the hand-over, e.g. the explorer model the plan goes into. */
  public Object owner() {
    return owner;
  }

  public String sopClass() {
    return sopClass;
  }

  public String sop() {
    return sop;
  }

  public String study() {
    return study;
  }

  public String series() {
    return series;
  }

  public String modality() {
    return modality;
  }

  public String seriesDescription() {
    return seriesDescription;
  }

  public String instanceNumber() {
    return instanceNumber;
  }

  public String patientId() {
    return patientId;
  }

  public String issuer() {
    return issuer;
  }

  public Plan plan(String planSop) {
    return plans.get(planSop);
  }

  public int planCount() {
    return plans.size();
  }

  /** Adds a plan unless one with the same instance UID is held; returns whether it was added. */
  boolean addPlan(Plan plan) {
    return plans.putIfAbsent(plan.sop(), plan) == null;
  }
}
