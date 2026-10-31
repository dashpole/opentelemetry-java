/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.trace;

import static io.opentelemetry.sdk.common.internal.ScopeConfiguratorBuilder.nameEquals;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.trace.internal.TracerConfig.disabled;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opentelemetry.api.incubator.trace.ExtendedTracer;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.internal.SdkTracerProviderUtil;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;

class SpanStabilityTest {

  @Test
  void defaultMinimumStability_SuppressesDevelopmentSpansAndPreservesParentContext() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

    ExtendedTracer tracer = (ExtendedTracer) tracerProvider.get("test");
    assertThat(tracer.isEnabled()).isTrue();
    assertThat(tracer.isEnabled(Stability.STABLE)).isTrue();
    assertThat(tracer.isEnabled(Stability.DEVELOPMENT)).isFalse();

    // Unset stability defaults to STABLE
    Span defaultSpan = tracer.spanBuilder("default-span").startSpan();
    assertThat(defaultSpan.isRecording()).isTrue();
    defaultSpan.end();

    // Root DEVELOPMENT span is suppressed and returns invalid non-recording SpanContext
    Span rootDevSpan =
        tracer.spanBuilder("root-dev-span").setStability(Stability.DEVELOPMENT).startSpan();
    assertThat(rootDevSpan.isRecording()).isFalse();
    assertThat(rootDevSpan.getSpanContext().isValid()).isFalse();
    rootDevSpan.end();

    // Parent STABLE -> Child DEVELOPMENT (suppressed) -> Grandchild STABLE preserves parent
    // SpanContext
    Span parentStableSpan =
        tracer.spanBuilder("parent-stable").setStability(Stability.STABLE).startSpan();
    try (Scope parentScope = parentStableSpan.makeCurrent()) {
      Span childDevSpan =
          tracer.spanBuilder("child-dev").setStability(Stability.DEVELOPMENT).startSpan();
      assertThat(childDevSpan.isRecording()).isFalse();
      assertThat(childDevSpan.getSpanContext()).isEqualTo(parentStableSpan.getSpanContext());

      try (Scope devScope = childDevSpan.makeCurrent()) {
        Span grandchildStableSpan =
            tracer.spanBuilder("grandchild-stable").setStability(Stability.STABLE).startSpan();
        assertThat(grandchildStableSpan.isRecording()).isTrue();
        grandchildStableSpan.end();
      }
      childDevSpan.end();
    } finally {
      parentStableSpan.end();
    }

    assertThat(exporter.getFinishedSpanItems())
        .satisfiesExactly(
            span -> assertThat(span).hasName("default-span"),
            span ->
                assertThat(span)
                    .hasName("grandchild-stable")
                    .hasTraceId(parentStableSpan.getSpanContext().getTraceId())
                    .hasParentSpanId(parentStableSpan.getSpanContext().getSpanId()),
            span ->
                assertThat(span)
                    .hasName("parent-stable")
                    .hasSpanId(parentStableSpan.getSpanContext().getSpanId()));
  }

  @Test
  void suppressedDevelopmentSpan_DoesNotInvokeSamplerOrSpanProcessor() {
    Sampler sampler = mock(Sampler.class);
    SpanProcessor processor = mock(SpanProcessor.class);
    SdkTracerProvider tracerProvider =
        SdkTracerProvider.builder().setSampler(sampler).addSpanProcessor(processor).build();

    ExtendedTracer tracer = (ExtendedTracer) tracerProvider.get("test");
    Span span = tracer.spanBuilder("dev-span").setStability(Stability.DEVELOPMENT).startSpan();
    span.end();

    verifyNoInteractions(sampler);
    verifyNoInteractions(processor);
  }

  @Test
  void developmentMinimumStability_EnablesBothStableAndDevelopmentSpans() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProviderBuilder builder =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
    SdkTracerProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkTracerProvider tracerProvider = builder.build();

    ExtendedTracer tracer = (ExtendedTracer) tracerProvider.get("test");
    assertThat(tracer.isEnabled(Stability.STABLE)).isTrue();
    assertThat(tracer.isEnabled(Stability.DEVELOPMENT)).isTrue();

    tracer.spanBuilder("stable-span").setStability(Stability.STABLE).startSpan().end();
    tracer.spanBuilder("dev-span").setStability(Stability.DEVELOPMENT).startSpan().end();

    assertThat(exporter.getFinishedSpanItems())
        .satisfiesExactly(
            span -> assertThat(span).hasName("stable-span"),
            span -> assertThat(span).hasName("dev-span"));
  }

  @Test
  void disabledTracer_ReturnsFalseForAllStabilities() {
    InMemorySpanExporter exporter = InMemorySpanExporter.create();
    SdkTracerProviderBuilder builder =
        SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .addTracerConfiguratorCondition(nameEquals("disabled-scope"), disabled());
    SdkTracerProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkTracerProvider tracerProvider = builder.build();

    ExtendedTracer tracer = (ExtendedTracer) tracerProvider.get("disabled-scope");
    assertThat(tracer.isEnabled()).isFalse();
    assertThat(tracer.isEnabled(Stability.STABLE)).isFalse();
    assertThat(tracer.isEnabled(Stability.DEVELOPMENT)).isFalse();
  }
}
