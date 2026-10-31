/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import io.opentelemetry.api.incubator.metrics.ExtendedMeter;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.internal.MeterConfig;
import io.opentelemetry.sdk.metrics.internal.export.RegisteredReader;
import io.opentelemetry.sdk.metrics.internal.state.MeterProviderSharedState;
import java.util.List;

/** SDK implementation of {@link ExtendedMeter}. */
final class ExtendedSdkMeter extends SdkMeter implements ExtendedMeter {

  ExtendedSdkMeter(
      MeterProviderSharedState meterProviderSharedState,
      InstrumentationScopeInfo instrumentationScopeInfo,
      List<RegisteredReader> registeredReaders,
      MeterConfig meterConfig) {
    super(meterProviderSharedState, instrumentationScopeInfo, registeredReaders, meterConfig);
  }
}
