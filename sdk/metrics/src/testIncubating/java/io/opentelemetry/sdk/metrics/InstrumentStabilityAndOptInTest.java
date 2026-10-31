/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.metrics;

import static io.opentelemetry.sdk.common.internal.ScopeConfiguratorBuilder.nameEquals;
import static io.opentelemetry.sdk.metrics.internal.MeterConfig.disabled;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;

import io.opentelemetry.api.incubator.metrics.ExtendedDoubleCounter;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleCounterBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleGauge;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleGaugeBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogram;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogramBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleUpDownCounter;
import io.opentelemetry.api.incubator.metrics.ExtendedDoubleUpDownCounterBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedLongCounter;
import io.opentelemetry.api.incubator.metrics.ExtendedLongCounterBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedLongGauge;
import io.opentelemetry.api.incubator.metrics.ExtendedLongGaugeBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedLongHistogram;
import io.opentelemetry.api.incubator.metrics.ExtendedLongHistogramBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedLongUpDownCounter;
import io.opentelemetry.api.incubator.metrics.ExtendedLongUpDownCounterBuilder;
import io.opentelemetry.api.incubator.metrics.ExtendedMeter;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import io.opentelemetry.sdk.common.internal.IncludeExcludePredicate;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.internal.SdkMeterProviderUtil;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InstrumentStabilityAndOptInTest {

  @Test
  void defaultMinimumStability_FourWayStabilityAndOptInMatrix() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    Meter meter = meterProvider.get("test");

    // 1. STABLE + optIn=false -> Enabled by default
    ExtendedLongCounter stableDefault =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("stable.default"))
                .setStability(Stability.STABLE)
                .setOptIn(false)
                .build();
    // 2. STABLE + optIn=true -> Disabled by default (Drop Aggregation)
    ExtendedLongCounter stableOptIn =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("stable.opt_in"))
                .setStability(Stability.STABLE)
                .setOptIn(true)
                .build();
    // 3. DEVELOPMENT + optIn=false -> Disabled when minimumStability=STABLE
    ExtendedLongCounter devDefault =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev.default"))
                .setStability(Stability.DEVELOPMENT)
                .setOptIn(false)
                .build();
    // 4. DEVELOPMENT + optIn=true -> Disabled when minimumStability=STABLE
    ExtendedLongCounter devOptIn =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev.opt_in"))
                .setStability(Stability.DEVELOPMENT)
                .setOptIn(true)
                .build();

    assertThat(stableDefault.isEnabled()).isTrue();
    assertThat(stableOptIn.isEnabled()).isFalse();
    assertThat(devDefault.isEnabled()).isFalse();
    assertThat(devOptIn.isEnabled()).isFalse();

    stableDefault.add(1);
    stableOptIn.add(1);
    devDefault.add(1);
    devOptIn.add(1);

    assertThat(reader.collectAllMetrics())
        .extracting(MetricData::getName)
        .containsExactly("stable.default");
  }

  @Test
  void developmentMinimumStability_FourWayStabilityAndOptInMatrix() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProviderBuilder builder = SdkMeterProvider.builder().registerMetricReader(reader);
    SdkMeterProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkMeterProvider meterProvider = builder.build();
    Meter meter = meterProvider.get("test");

    // 1. STABLE + optIn=false -> Enabled
    ExtendedLongCounter stableDefault =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("stable.default"))
                .setStability(Stability.STABLE)
                .setOptIn(false)
                .build();
    // 2. STABLE + optIn=true -> Still disabled even when minimumStability=DEVELOPMENT
    ExtendedLongCounter stableOptIn =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("stable.opt_in"))
                .setStability(Stability.STABLE)
                .setOptIn(true)
                .build();
    // 3. DEVELOPMENT + optIn=false -> Enabled when minimumStability=DEVELOPMENT
    ExtendedLongCounter devDefault =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev.default"))
                .setStability(Stability.DEVELOPMENT)
                .setOptIn(false)
                .build();
    // 4. DEVELOPMENT + optIn=true -> Still disabled even when minimumStability=DEVELOPMENT
    ExtendedLongCounter devOptIn =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev.opt_in"))
                .setStability(Stability.DEVELOPMENT)
                .setOptIn(true)
                .build();

    assertThat(stableDefault.isEnabled()).isTrue();
    assertThat(stableOptIn.isEnabled()).isFalse();
    assertThat(devDefault.isEnabled()).isTrue();
    assertThat(devOptIn.isEnabled()).isFalse();

    stableDefault.add(1);
    stableOptIn.add(1);
    devDefault.add(1);
    devOptIn.add(1);

    assertThat(reader.collectAllMetrics())
        .extracting(MetricData::getName)
        .containsExactlyInAnyOrder("stable.default", "dev.default");
  }

  @Test
  void viewEnabled_OverridesStabilityAndOptIn() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder()
            .registerMetricReader(reader)
            // View with enabled=true enables a DEVELOPMENT instrument under minimumStability=STABLE
            .registerView(
                InstrumentSelector.builder().setName("dev.enabled_by_view").build(),
                SdkMeterProviderUtil.setViewEnabled(View.builder(), true).build())
            // View with enabled=true enables an OptIn=true instrument
            .registerView(
                InstrumentSelector.builder().setName("opt_in.enabled_by_view").build(),
                SdkMeterProviderUtil.setViewEnabled(View.builder(), true).build())
            // View with enabled=true enables a DEVELOPMENT + OptIn=true instrument
            .registerView(
                InstrumentSelector.builder().setName("dev_opt_in.enabled_by_view").build(),
                SdkMeterProviderUtil.setViewEnabled(View.builder(), true).build())
            // View with enabled=false disables a STABLE instrument even if aggregation is set
            .registerView(
                InstrumentSelector.builder().setName("stable.disabled_by_view").build(),
                SdkMeterProviderUtil.setViewEnabled(
                        View.builder().setAggregation(Aggregation.sum()), false)
                    .build())
            // View that sets aggregation/description WITHOUT setting enabled=true does NOT enable
            // a DEVELOPMENT or OptIn=true instrument
            .registerView(
                InstrumentSelector.builder().setName("dev.view_without_enabled").build(),
                View.builder()
                    .setDescription("custom description")
                    .setAggregation(Aggregation.sum())
                    .build())
            .registerView(
                InstrumentSelector.builder().setName("opt_in.view_without_enabled").build(),
                View.builder()
                    .setDescription("custom description")
                    .setAggregation(Aggregation.sum())
                    .build())
            .build();

    Meter meter = meterProvider.get("test");

    ExtendedLongCounter devEnabled =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev.enabled_by_view"))
                .setStability(Stability.DEVELOPMENT)
                .build();
    ExtendedLongCounter optInEnabled =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("opt_in.enabled_by_view"))
                .setOptIn(true)
                .build();
    ExtendedLongCounter devOptInEnabled =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev_opt_in.enabled_by_view"))
                .setStability(Stability.DEVELOPMENT)
                .setOptIn(true)
                .build();
    ExtendedLongCounter stableDisabled =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("stable.disabled_by_view"))
                .setStability(Stability.STABLE)
                .build();
    ExtendedLongCounter devViewWithoutEnabled =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("dev.view_without_enabled"))
                .setStability(Stability.DEVELOPMENT)
                .build();
    ExtendedLongCounter optInViewWithoutEnabled =
        (ExtendedLongCounter)
            ((ExtendedLongCounterBuilder) meter.counterBuilder("opt_in.view_without_enabled"))
                .setOptIn(true)
                .build();

    assertThat(devEnabled.isEnabled()).isTrue();
    assertThat(optInEnabled.isEnabled()).isTrue();
    assertThat(devOptInEnabled.isEnabled()).isTrue();
    assertThat(stableDisabled.isEnabled()).isFalse();
    assertThat(devViewWithoutEnabled.isEnabled()).isFalse();
    assertThat(optInViewWithoutEnabled.isEnabled()).isFalse();

    devEnabled.add(1);
    optInEnabled.add(1);
    devOptInEnabled.add(1);
    stableDisabled.add(1);
    devViewWithoutEnabled.add(1);
    optInViewWithoutEnabled.add(1);

    assertThat(reader.collectAllMetrics())
        .extracting(MetricData::getName)
        .containsExactlyInAnyOrder(
            "dev.enabled_by_view", "opt_in.enabled_by_view", "dev_opt_in.enabled_by_view");
  }

  @Test
  void allInstrumentBuildersAndAsyncCallbacks_SupportStabilityAndOptIn() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider meterProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    Meter meter = meterProvider.get("test");

    ExtendedDoubleCounter doubleCounter =
        (ExtendedDoubleCounter)
            ((ExtendedDoubleCounterBuilder) meter.counterBuilder("double.counter").ofDoubles())
                .setStability(Stability.DEVELOPMENT)
                .build();
    ExtendedLongUpDownCounter longUpDownCounter =
        (ExtendedLongUpDownCounter)
            ((ExtendedLongUpDownCounterBuilder) meter.upDownCounterBuilder("long.updowncounter"))
                .setStability(Stability.DEVELOPMENT)
                .build();
    ExtendedDoubleUpDownCounter doubleUpDownCounter =
        (ExtendedDoubleUpDownCounter)
            ((ExtendedDoubleUpDownCounterBuilder)
                    meter.upDownCounterBuilder("double.updowncounter").ofDoubles())
                .setOptIn(true)
                .build();
    ExtendedDoubleHistogram doubleHistogram =
        (ExtendedDoubleHistogram)
            ((ExtendedDoubleHistogramBuilder) meter.histogramBuilder("double.histogram"))
                .setStability(Stability.DEVELOPMENT)
                .build();
    ExtendedLongHistogram longHistogram =
        (ExtendedLongHistogram)
            ((ExtendedLongHistogramBuilder) meter.histogramBuilder("long.histogram").ofLongs())
                .setOptIn(true)
                .build();
    ExtendedDoubleGauge doubleGauge =
        (ExtendedDoubleGauge)
            ((ExtendedDoubleGaugeBuilder) meter.gaugeBuilder("double.gauge"))
                .setStability(Stability.DEVELOPMENT)
                .build();
    ExtendedLongGauge longGauge =
        (ExtendedLongGauge)
            ((ExtendedLongGaugeBuilder) meter.gaugeBuilder("long.gauge").ofLongs())
                .setOptIn(true)
                .build();

    AtomicBoolean devCallbackInvoked = new AtomicBoolean(false);
    ((ExtendedLongGaugeBuilder) meter.gaugeBuilder("async.dev.gauge").ofLongs())
        .setStability(Stability.DEVELOPMENT)
        .buildWithCallback(
            measurement -> {
              devCallbackInvoked.set(true);
              measurement.record(42);
            });

    assertThat(doubleCounter.isEnabled()).isFalse();
    assertThat(longUpDownCounter.isEnabled()).isFalse();
    assertThat(doubleUpDownCounter.isEnabled()).isFalse();
    assertThat(doubleHistogram.isEnabled()).isFalse();
    assertThat(longHistogram.isEnabled()).isFalse();
    assertThat(doubleGauge.isEnabled()).isFalse();
    assertThat(longGauge.isEnabled()).isFalse();

    assertThat(reader.collectAllMetrics()).isEmpty();
    assertThat(devCallbackInvoked.get()).isFalse();
  }

  @Test
  void meterAttributeEnabled_DefaultAndDevelopmentMinimumStability() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider defaultProvider =
        SdkMeterProvider.builder().registerMetricReader(reader).build();
    ExtendedMeter defaultMeter = (ExtendedMeter) defaultProvider.get("test");

    assertThat(defaultMeter.attributeEnabled("attr")).isTrue();
    assertThat(defaultMeter.attributeEnabled("attr", Stability.STABLE)).isTrue();
    assertThat(defaultMeter.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();

    SdkMeterProviderBuilder devBuilder = SdkMeterProvider.builder().registerMetricReader(reader);
    SdkMeterProviderUtil.setMinimumStability(devBuilder, Stability.DEVELOPMENT);
    SdkMeterProvider devProvider = devBuilder.build();
    ExtendedMeter devMeter = (ExtendedMeter) devProvider.get("test");

    assertThat(devMeter.attributeEnabled("attr")).isTrue();
    assertThat(devMeter.attributeEnabled("attr", Stability.STABLE)).isTrue();
    assertThat(devMeter.attributeEnabled("attr", Stability.DEVELOPMENT)).isTrue();
  }

  @Test
  void meterAttributeEnabled_DisabledMeterNoReadersAndNoop() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProviderBuilder builder =
        SdkMeterProvider.builder()
            .registerMetricReader(reader)
            .addMeterConfiguratorCondition(nameEquals("disabled-scope"), disabled());
    SdkMeterProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkMeterProvider provider = builder.build();

    ExtendedMeter disabledMeter = (ExtendedMeter) provider.get("disabled-scope");
    assertThat(disabledMeter.attributeEnabled("attr")).isFalse();
    assertThat(disabledMeter.attributeEnabled("attr", Stability.STABLE)).isFalse();
    assertThat(disabledMeter.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();

    SdkMeterProviderBuilder noReaderBuilder = SdkMeterProvider.builder();
    SdkMeterProviderUtil.setMinimumStability(noReaderBuilder, Stability.DEVELOPMENT);
    SdkMeterProvider noReaderProvider = noReaderBuilder.build();
    ExtendedMeter noReaderMeter = (ExtendedMeter) noReaderProvider.get("test");
    assertThat(noReaderMeter.attributeEnabled("attr")).isFalse();
    assertThat(noReaderMeter.attributeEnabled("attr", Stability.STABLE)).isFalse();
    assertThat(noReaderMeter.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();

    ExtendedMeter noopMeter = (ExtendedMeter) MeterProvider.noop().get("test");
    assertThat(noopMeter.attributeEnabled("attr")).isFalse();
    assertThat(noopMeter.attributeEnabled("attr", Stability.STABLE)).isFalse();
    assertThat(noopMeter.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();
  }

  @Test
  void meterAttributeEnabled_ViewAttributeFilterInclusionAndExclusion() {
    InMemoryMetricReader reader = InMemoryMetricReader.create();
    SdkMeterProvider provider =
        SdkMeterProvider.builder()
            .registerMetricReader(reader)
            // Active view for "scope-with-include" that explicitly includes "dev.included" and
            // "stable.included"
            .registerView(
                InstrumentSelector.builder().setMeterName("scope-with-include").build(),
                View.builder()
                    .setAttributeFilter(
                        new HashSet<>(Arrays.asList("dev.included", "stable.included")))
                    .build())
            // Active view for "scope-with-exclude-only" that only excludes "excluded.attr"
            .registerView(
                InstrumentSelector.builder().setMeterName("scope-with-exclude-only").build(),
                View.builder()
                    .setAttributeFilter(
                        IncludeExcludePredicate.createExactMatching(
                            null, Collections.singleton("excluded.attr")))
                    .build())
            // Active view scoped to a single instrument of "scope-with-instrument-view"; it matches
            // the meter, so its included attribute is enabled for the whole meter.
            .registerView(
                InstrumentSelector.builder()
                    .setMeterName("scope-with-instrument-view")
                    .setName("one.instrument")
                    .build(),
                View.builder().setAttributeFilter(Collections.singleton("dev.included")).build())
            // Opaque predicate filters (here: exclude one key) are not explicit inclusion
            .registerView(
                InstrumentSelector.builder().setMeterName("scope-with-predicate").build(),
                View.builder().setAttributeFilter(key -> !key.equals("excluded.attr")).build())
            // View with enabled=false is not active, even if it includes the key
            .registerView(
                InstrumentSelector.builder().setMeterName("scope-with-disabled-view").build(),
                SdkMeterProviderUtil.setViewEnabled(
                        View.builder().setAttributeFilter(Collections.singleton("dev.included")),
                        false)
                    .build())
            // Dropped view for "scope-with-dropped-view" should not enable development attributes
            .registerView(
                InstrumentSelector.builder().setMeterName("scope-with-dropped-view").build(),
                View.builder()
                    .setAggregation(Aggregation.drop())
                    .setAttributeFilter(Collections.singleton("dev.included"))
                    .build())
            .build();

    ExtendedMeter includeMeter = (ExtendedMeter) provider.get("scope-with-include");
    // Explicitly included DEVELOPMENT attribute is enabled even when minimumStability=STABLE
    assertThat(includeMeter.attributeEnabled("dev.included", Stability.DEVELOPMENT)).isTrue();
    assertThat(includeMeter.attributeEnabled("stable.included", Stability.STABLE)).isTrue();
    // DEVELOPMENT attributes not explicitly included by a matching view return false
    assertThat(includeMeter.attributeEnabled("dev.other", Stability.DEVELOPMENT)).isFalse();
    // STABLE attributes are enabled even if a view filters them out
    assertThat(includeMeter.attributeEnabled("stable.excluded", Stability.STABLE)).isTrue();

    ExtendedMeter excludeOnlyMeter = (ExtendedMeter) provider.get("scope-with-exclude-only");
    assertThat(excludeOnlyMeter.attributeEnabled("excluded.attr", Stability.STABLE)).isTrue();
    assertThat(excludeOnlyMeter.attributeEnabled("allowed.attr", Stability.STABLE)).isTrue();
    // Exclude-only filter does not explicitly include "allowed.attr", so DEVELOPMENT remains false
    assertThat(excludeOnlyMeter.attributeEnabled("allowed.attr", Stability.DEVELOPMENT)).isFalse();

    ExtendedMeter instrumentViewMeter = (ExtendedMeter) provider.get("scope-with-instrument-view");
    assertThat(instrumentViewMeter.attributeEnabled("dev.included", Stability.DEVELOPMENT))
        .isTrue();
    assertThat(instrumentViewMeter.attributeEnabled("dev.other", Stability.DEVELOPMENT)).isFalse();
    // Other instruments of the meter use the default view, so STABLE attributes stay enabled
    assertThat(instrumentViewMeter.attributeEnabled("stable.attr", Stability.STABLE)).isTrue();

    // Views matching a different meter do not enable DEVELOPMENT attributes
    ExtendedMeter otherMeter = (ExtendedMeter) provider.get("other-scope");
    assertThat(otherMeter.attributeEnabled("dev.included", Stability.DEVELOPMENT)).isFalse();

    ExtendedMeter predicateMeter = (ExtendedMeter) provider.get("scope-with-predicate");
    assertThat(predicateMeter.attributeEnabled("dev.attr", Stability.DEVELOPMENT)).isFalse();
    assertThat(predicateMeter.attributeEnabled("stable.attr", Stability.STABLE)).isTrue();

    ExtendedMeter disabledViewMeter = (ExtendedMeter) provider.get("scope-with-disabled-view");
    assertThat(disabledViewMeter.attributeEnabled("dev.included", Stability.DEVELOPMENT)).isFalse();

    ExtendedMeter droppedViewMeter = (ExtendedMeter) provider.get("scope-with-dropped-view");
    assertThat(droppedViewMeter.attributeEnabled("dev.included", Stability.DEVELOPMENT)).isFalse();
    assertThat(droppedViewMeter.attributeEnabled("stable.attr", Stability.STABLE)).isTrue();
  }
}
