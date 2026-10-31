/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.trace;

import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.trace.Tracer;

/** Extended {@link Tracer} with experimental APIs. */
public interface ExtendedTracer extends Tracer {

  /**
   * Returns {@code true} if the tracer is enabled for the given {@link Stability}.
   *
   * @see #isEnabled()
   */
  default boolean isEnabled(Stability stability) {
    return isEnabled();
  }

  /**
   * Returns {@code true} if the attribute with the given {@code key} and {@code stability} is
   * enabled for this tracer.
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
   * is enabled for this tracer.
   *
   * @see #attributeEnabled(String, Stability)
   */
  default boolean attributeEnabled(String key) {
    return attributeEnabled(key, Stability.STABLE);
  }

  @Override
  ExtendedSpanBuilder spanBuilder(String spanName);
}
