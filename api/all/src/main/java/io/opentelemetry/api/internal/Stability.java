/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.internal;

/**
 * The stability level of an OpenTelemetry telemetry signal or instrument.
 *
 * <p>Constants are ordered from least stable ({@link #DEVELOPMENT}) to most stable ({@link
 * #STABLE}).
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
public enum Stability {
  /**
   * Indicates that the telemetry signal or instrument is experimental / in development and remains
   * subject to breaking changes.
   */
  DEVELOPMENT,

  /** Indicates that the telemetry signal or instrument is stable. */
  STABLE
}
