/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal;

import static java.util.Objects.requireNonNull;

import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporterBuilder;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporterBuilder;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporterBuilder;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporterBuilder;
import io.opentelemetry.sdk.common.export.internal.Authenticator;
import java.lang.reflect.Field;

/**
 * A collection of methods that allow use of experimental OTLP exporter authentication features
 * prior to availability in public APIs.
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
public final class OtlpExporterUtil {

  private OtlpExporterUtil() {}

  /**
   * Reflectively set the {@link Authenticator} on the {@link OtlpHttpSpanExporterBuilder}.
   *
   * @param builder the exporter builder
   * @param authenticator the authenticator
   * @return the exporter builder
   */
  public static OtlpHttpSpanExporterBuilder setAuthenticator(
      OtlpHttpSpanExporterBuilder builder, Authenticator authenticator) {
    setHttpAuthenticator(OtlpHttpSpanExporterBuilder.class, builder, authenticator);
    return builder;
  }

  /**
   * Reflectively set the {@link Authenticator} on the {@link OtlpGrpcSpanExporterBuilder}.
   *
   * @param builder the exporter builder
   * @param authenticator the authenticator
   * @return the exporter builder
   */
  public static OtlpGrpcSpanExporterBuilder setAuthenticator(
      OtlpGrpcSpanExporterBuilder builder, Authenticator authenticator) {
    setGrpcAuthenticator(OtlpGrpcSpanExporterBuilder.class, builder, authenticator);
    return builder;
  }

  /**
   * Reflectively set the {@link Authenticator} on the {@link OtlpHttpMetricExporterBuilder}.
   *
   * @param builder the exporter builder
   * @param authenticator the authenticator
   * @return the exporter builder
   */
  public static OtlpHttpMetricExporterBuilder setAuthenticator(
      OtlpHttpMetricExporterBuilder builder, Authenticator authenticator) {
    setHttpAuthenticator(OtlpHttpMetricExporterBuilder.class, builder, authenticator);
    return builder;
  }

  /**
   * Reflectively set the {@link Authenticator} on the {@link OtlpGrpcMetricExporterBuilder}.
   *
   * @param builder the exporter builder
   * @param authenticator the authenticator
   * @return the exporter builder
   */
  public static OtlpGrpcMetricExporterBuilder setAuthenticator(
      OtlpGrpcMetricExporterBuilder builder, Authenticator authenticator) {
    setGrpcAuthenticator(OtlpGrpcMetricExporterBuilder.class, builder, authenticator);
    return builder;
  }

  /**
   * Reflectively set the {@link Authenticator} on the {@link OtlpHttpLogRecordExporterBuilder}.
   *
   * @param builder the exporter builder
   * @param authenticator the authenticator
   * @return the exporter builder
   */
  public static OtlpHttpLogRecordExporterBuilder setAuthenticator(
      OtlpHttpLogRecordExporterBuilder builder, Authenticator authenticator) {
    setHttpAuthenticator(OtlpHttpLogRecordExporterBuilder.class, builder, authenticator);
    return builder;
  }

  /**
   * Reflectively set the {@link Authenticator} on the {@link OtlpGrpcLogRecordExporterBuilder}.
   *
   * @param builder the exporter builder
   * @param authenticator the authenticator
   * @return the exporter builder
   */
  public static OtlpGrpcLogRecordExporterBuilder setAuthenticator(
      OtlpGrpcLogRecordExporterBuilder builder, Authenticator authenticator) {
    setGrpcAuthenticator(OtlpGrpcLogRecordExporterBuilder.class, builder, authenticator);
    return builder;
  }

  private static <T> void setHttpAuthenticator(
      Class<T> builderClass, T builder, Authenticator authenticator) {
    requireNonNull(builder, "builder");
    requireNonNull(authenticator, "authenticator");
    try {
      Field delegateField = builderClass.getDeclaredField("delegate");
      delegateField.setAccessible(true);
      HttpExporterBuilder delegate = (HttpExporterBuilder) delegateField.get(builder);
      delegate.setAuthenticator(authenticator);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException(
          "Error setting authenticator on " + builderClass.getSimpleName(), e);
    }
  }

  private static <T> void setGrpcAuthenticator(
      Class<T> builderClass, T builder, Authenticator authenticator) {
    requireNonNull(builder, "builder");
    requireNonNull(authenticator, "authenticator");
    try {
      Field delegateField = builderClass.getDeclaredField("delegate");
      delegateField.setAccessible(true);
      GrpcExporterBuilder delegate = (GrpcExporterBuilder) delegateField.get(builder);
      delegate.setAuthenticator(authenticator);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new IllegalStateException(
          "Error setting authenticator on " + builderClass.getSimpleName(), e);
    }
  }
}
