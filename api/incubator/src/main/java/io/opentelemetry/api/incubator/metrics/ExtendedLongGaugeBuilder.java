/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import java.util.List;

/** Extended {@link LongGaugeBuilder} with experimental APIs. */
public interface ExtendedLongGaugeBuilder extends LongGaugeBuilder {

  /**
   * Specify the attribute advice, which suggests the recommended set of attribute keys to be used
   * for this gauge.
   */
  default ExtendedLongGaugeBuilder setAttributesAdvice(List<AttributeKey<?>> attributes) {
    return this;
  }

  /**
   * Specify the stability advisory parameter for this gauge. If not set, defaults to {@link
   * Stability#STABLE}.
   */
  default ExtendedLongGaugeBuilder setStability(Stability stability) {
    return this;
  }

  /**
   * Specify the opt-in advisory parameter for this gauge. If not set, defaults to {@code false}.
   */
  default ExtendedLongGaugeBuilder setOptIn(boolean optIn) {
    return this;
  }
}
