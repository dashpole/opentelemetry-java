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

  @Override
  ExtendedSpanBuilder spanBuilder(String spanName);
}
