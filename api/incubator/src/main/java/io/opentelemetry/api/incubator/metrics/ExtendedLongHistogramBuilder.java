/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.LongHistogramBuilder;
import java.util.List;

/** Extended {@link LongHistogramBuilder} with experimental APIs. */
public interface ExtendedLongHistogramBuilder extends LongHistogramBuilder {

  /**
   * Specify the attribute advice, which suggests the recommended set of attribute keys to be used
   * for this histogram.
   */
  default ExtendedLongHistogramBuilder setAttributesAdvice(List<AttributeKey<?>> attributes) {
    return this;
  }

  /**
   * Specify the stability advisory parameter for this histogram. If not set, defaults to {@link
   * Stability#STABLE}.
   */
  default ExtendedLongHistogramBuilder setStability(Stability stability) {
    return this;
  }

  /**
   * Specify the opt-in advisory parameter for this histogram. If not set, defaults to {@code
   * false}.
   */
  default ExtendedLongHistogramBuilder setOptIn(boolean optIn) {
    return this;
  }
}
