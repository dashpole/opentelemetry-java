/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import java.util.List;

/** Extended {@link DoubleHistogramBuilder} with experimental APIs. */
public interface ExtendedDoubleHistogramBuilder extends DoubleHistogramBuilder {

  /**
   * Specify the attribute advice, which suggests the recommended set of attribute keys to be used
   * for this histogram.
   */
  default ExtendedDoubleHistogramBuilder setAttributesAdvice(List<AttributeKey<?>> attributes) {
    return this;
  }

  /**
   * Specify the stability advisory parameter for this histogram. If not set, defaults to {@link
   * Stability#STABLE}.
   */
  default ExtendedDoubleHistogramBuilder setStability(Stability stability) {
    return this;
  }

  /**
   * Specify the opt-in advisory parameter for this histogram. If not set, defaults to {@code
   * false}.
   */
  default ExtendedDoubleHistogramBuilder setOptIn(boolean optIn) {
    return this;
  }
}
