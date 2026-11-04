/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.metrics;

import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.context.Context;

/** Extended {@link Meter} with experimental APIs. */
public interface ExtendedMeter extends Meter {

  /**
   * Returns {@code true} if the attribute with the given {@code key} and {@code stability} is
   * enabled for this meter and {@code context}.
   */
  default boolean attributeEnabled(String key, Context context, Stability stability) {
    return attributeEnabled(key, stability);
  }

  /**
   * Returns {@code true} if the attribute with the given {@code key} and {@code stability} is
   * enabled for this meter.
   *
   * <p>Instrumentation should call this before computing or attaching {@link Stability#DEVELOPMENT}
   * attributes. The returned value can change over time, so it should be called at the recording
   * call site rather than cached.
   */
  default boolean attributeEnabled(String key, Stability stability) {
    return false;
  }

  /**
   * Returns {@code true} if the attribute with the given {@code key} and {@link Stability#STABLE}
   * is enabled for this meter.
   *
   * @see #attributeEnabled(String, Stability)
   */
  default boolean attributeEnabled(String key) {
    return attributeEnabled(key, Stability.STABLE);
  }
}
