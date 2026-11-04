/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.logs;

import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;

/** Extended {@link Logger} with experimental APIs. */
@SuppressWarnings("InconsistentOverloads")
public interface ExtendedLogger extends Logger {

  /**
   * Returns {@code true} if the logger is enabled for the given {@code context}, {@code severity},
   * and {@code stability}.
   *
   * @see #isEnabled(Severity, Context)
   */
  default boolean isEnabled(Severity severity, Context context, Stability stability) {
    return isEnabled(severity, context);
  }

  /**
   * Returns {@code true} if the logger is enabled for the current {@link Context}, given {@code
   * severity}, and given {@code stability}.
   *
   * @see #isEnabled(Severity)
   */
  default boolean isEnabled(Severity severity, Stability stability) {
    return isEnabled(severity, Context.current(), stability);
  }

  /**
   * Returns {@code true} if the attribute with the given {@code key} and {@code stability} is
   * enabled for this logger and {@code context}.
   */
  default boolean attributeEnabled(String key, Context context, Stability stability) {
    return attributeEnabled(key, stability);
  }

  /**
   * Returns {@code true} if the attribute with the given {@code key} and {@code stability} is
   * enabled for this logger.
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
   * is enabled for this logger.
   *
   * @see #attributeEnabled(String, Stability)
   */
  default boolean attributeEnabled(String key) {
    return attributeEnabled(key, Stability.STABLE);
  }

  @Override
  ExtendedLogRecordBuilder logRecordBuilder();
}
