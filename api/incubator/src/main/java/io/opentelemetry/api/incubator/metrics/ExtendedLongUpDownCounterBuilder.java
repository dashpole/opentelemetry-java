/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.LongUpDownCounterBuilder;
import java.util.List;

/** Extended {@link LongUpDownCounterBuilder} with experimental APIs. */
public interface ExtendedLongUpDownCounterBuilder extends LongUpDownCounterBuilder {

  /**
   * Specify the attribute advice, which suggests the recommended set of attribute keys to be used
   * for this up down counter.
   */
  default ExtendedLongUpDownCounterBuilder setAttributesAdvice(List<AttributeKey<?>> attributes) {
    return this;
  }

  /**
   * Specify the stability advisory parameter for this up down counter. If not set, defaults to
   * {@link Stability#STABLE}.
   */
  default ExtendedLongUpDownCounterBuilder setStability(Stability stability) {
    return this;
  }

  /**
   * Specify the opt-in advisory parameter for this up down counter. If not set, defaults to {@code
   * false}.
   */
  default ExtendedLongUpDownCounterBuilder setOptIn(boolean optIn) {
    return this;
  }
}
