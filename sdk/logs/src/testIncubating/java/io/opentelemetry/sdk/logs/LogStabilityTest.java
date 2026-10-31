/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.logs;

import static io.opentelemetry.sdk.common.internal.ScopeConfiguratorBuilder.nameEquals;
import static io.opentelemetry.sdk.logs.internal.LoggerConfig.disabled;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opentelemetry.api.incubator.logs.ExtendedLogger;
import io.opentelemetry.api.internal.Stability;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.logs.internal.LoggerConfig;
import io.opentelemetry.sdk.logs.internal.SdkLoggerProviderUtil;
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter;
import org.junit.jupiter.api.Test;

class LogStabilityTest {

  @Test
  void defaultMinimumStability_SuppressesDevelopmentLogs() {
    InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build();

    ExtendedLogger logger = (ExtendedLogger) loggerProvider.get("test");
    assertThat(logger.isEnabled(Severity.INFO)).isTrue();
    assertThat(logger.isEnabled(Severity.INFO, Stability.STABLE)).isTrue();
    assertThat(logger.isEnabled(Severity.INFO, Context.current(), Stability.STABLE)).isTrue();
    assertThat(logger.isEnabled(Severity.INFO, Stability.DEVELOPMENT)).isFalse();
    assertThat(logger.isEnabled(Severity.INFO, Context.current(), Stability.DEVELOPMENT)).isFalse();
    assertThat(logger.attributeEnabled("attr")).isTrue();
    assertThat(logger.attributeEnabled("attr", Stability.STABLE)).isTrue();
    assertThat(logger.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();

    // Unset stability defaults to STABLE
    logger.logRecordBuilder().setSeverity(Severity.INFO).setBody("default-log").emit();
    logger
        .logRecordBuilder()
        .setSeverity(Severity.INFO)
        .setStability(Stability.STABLE)
        .setBody("stable-log")
        .emit();
    logger
        .logRecordBuilder()
        .setSeverity(Severity.INFO)
        .setStability(Stability.DEVELOPMENT)
        .setBody("dev-log")
        .emit();

    assertThat(exporter.getFinishedLogRecordItems())
        .satisfiesExactly(
            log -> assertThat(log).hasBody("default-log"),
            log -> assertThat(log).hasBody("stable-log"));
  }

  @Test
  void suppressedDevelopmentLog_DoesNotInvokeLogRecordProcessor() {
    LogRecordProcessor processor = mock(LogRecordProcessor.class);
    SdkLoggerProvider loggerProvider =
        SdkLoggerProvider.builder().addLogRecordProcessor(processor).build();

    ExtendedLogger logger = (ExtendedLogger) loggerProvider.get("test");
    logger
        .logRecordBuilder()
        .setSeverity(Severity.INFO)
        .setStability(Stability.DEVELOPMENT)
        .setBody("dev-log")
        .emit();

    verifyNoInteractions(processor);
  }

  @Test
  void developmentMinimumStability_EnablesBothStableAndDevelopmentLogs() {
    InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    SdkLoggerProviderBuilder builder =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter));
    SdkLoggerProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkLoggerProvider loggerProvider = builder.build();

    ExtendedLogger logger = (ExtendedLogger) loggerProvider.get("test");
    assertThat(logger.isEnabled(Severity.INFO, Stability.STABLE)).isTrue();
    assertThat(logger.isEnabled(Severity.INFO, Stability.DEVELOPMENT)).isTrue();
    assertThat(logger.attributeEnabled("attr")).isTrue();
    assertThat(logger.attributeEnabled("attr", Stability.STABLE)).isTrue();
    assertThat(logger.attributeEnabled("attr", Stability.DEVELOPMENT)).isTrue();

    logger
        .logRecordBuilder()
        .setSeverity(Severity.INFO)
        .setStability(Stability.STABLE)
        .setBody("stable-log")
        .emit();
    logger
        .logRecordBuilder()
        .setSeverity(Severity.INFO)
        .setStability(Stability.DEVELOPMENT)
        .setBody("dev-log")
        .emit();

    assertThat(exporter.getFinishedLogRecordItems())
        .satisfiesExactly(
            log -> assertThat(log).hasBody("stable-log"),
            log -> assertThat(log).hasBody("dev-log"));
  }

  @Test
  void loggerConfigInteractions_WithMinimumStability() {
    InMemoryLogRecordExporter exporter = InMemoryLogRecordExporter.create();
    SdkLoggerProviderBuilder builder =
        SdkLoggerProvider.builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .addLoggerConfiguratorCondition(
                nameEquals("warn-only"),
                LoggerConfig.builder().setMinimumSeverity(Severity.WARN).build())
            .addLoggerConfiguratorCondition(nameEquals("disabled-scope"), disabled());
    SdkLoggerProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkLoggerProvider loggerProvider = builder.build();

    ExtendedLogger warnLogger = (ExtendedLogger) loggerProvider.get("warn-only");
    assertThat(warnLogger.isEnabled(Severity.INFO, Stability.DEVELOPMENT)).isFalse();
    assertThat(warnLogger.isEnabled(Severity.WARN, Stability.DEVELOPMENT)).isTrue();

    ExtendedLogger disabledLogger = (ExtendedLogger) loggerProvider.get("disabled-scope");
    assertThat(disabledLogger.isEnabled(Severity.ERROR, Stability.STABLE)).isFalse();
    assertThat(disabledLogger.isEnabled(Severity.ERROR, Stability.DEVELOPMENT)).isFalse();
    assertThat(disabledLogger.attributeEnabled("attr")).isFalse();
    assertThat(disabledLogger.attributeEnabled("attr", Stability.STABLE)).isFalse();
    assertThat(disabledLogger.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();
  }

  @Test
  void noLogRecordProcessorsAndNoopLogger_AttributeEnabledReturnsFalse() {
    SdkLoggerProviderBuilder builder = SdkLoggerProvider.builder();
    SdkLoggerProviderUtil.setMinimumStability(builder, Stability.DEVELOPMENT);
    SdkLoggerProvider providerWithoutProcessors = builder.build();

    ExtendedLogger loggerWithoutProcessors = (ExtendedLogger) providerWithoutProcessors.get("test");
    assertThat(loggerWithoutProcessors.isEnabled(Severity.INFO, Stability.STABLE)).isFalse();
    assertThat(loggerWithoutProcessors.isEnabled(Severity.INFO, Stability.DEVELOPMENT)).isFalse();
    assertThat(loggerWithoutProcessors.attributeEnabled("attr")).isFalse();
    assertThat(loggerWithoutProcessors.attributeEnabled("attr", Stability.STABLE)).isFalse();
    assertThat(loggerWithoutProcessors.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();

    ExtendedLogger noopLogger = (ExtendedLogger) LoggerProvider.noop().get("test");
    assertThat(noopLogger.attributeEnabled("attr")).isFalse();
    assertThat(noopLogger.attributeEnabled("attr", Stability.STABLE)).isFalse();
    assertThat(noopLogger.attributeEnabled("attr", Stability.DEVELOPMENT)).isFalse();
  }
}
