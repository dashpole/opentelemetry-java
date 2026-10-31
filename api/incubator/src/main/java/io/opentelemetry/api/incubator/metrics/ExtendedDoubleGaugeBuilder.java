/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.api.incubator.metrics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import java.util.List;

/** Extended {@link DoubleGaugeBuilder} with experimental APIs. */
public interface ExtendedDoubleGaugeBuilder extends DoubleGaugeBuilder {

  /**
   * Specify the attribute advice, which suggests the recommended set of attribute keys to be used
   * for this gauge.
   */
  default ExtendedDoubleGaugeBuilder setAttributesAdvice(List<AttributeKey<?>> attributes) {
    return this;
  }

  /**
   * Specify the stability advisory parameter for this gauge. If not set, defaults to {@link
   * Stability#STABLE}.
   */
  default ExtendedDoubleGaugeBuilder setStability(Stability stability) {
    return this;
  }

  /**
   * Specify the opt-in advisory parameter for this gauge. If not set, defaults to {@code false}.
   */
  default ExtendedDoubleGaugeBuilder setOptIn(boolean optIn) {
    return this;
  }
}
