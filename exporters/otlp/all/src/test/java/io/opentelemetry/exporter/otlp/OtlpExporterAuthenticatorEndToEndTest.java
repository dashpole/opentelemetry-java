/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.assertj.core.api.Assertions.assertThat;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.internal.GrpcExporter;
import io.opentelemetry.exporter.otlp.internal.HttpExporter;
import io.opentelemetry.exporter.otlp.internal.OtlpExporterUtil;
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.internal.testing.slf4j.SuppressLogger;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.common.export.internal.Authenticator;
import io.opentelemetry.sdk.common.export.internal.OAuth2ClientCredentialsAuthenticator;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableLongPointData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData;
import io.opentelemetry.sdk.metrics.internal.data.ImmutableSumData;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.logs.TestLogRecordData;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end tests verifying {@link Authenticator} and {@link OAuth2ClientCredentialsAuthenticator}
 * across all 6 OTLP exporters (HTTP and gRPC for traces, metrics, and logs).
 */
@SuppressLogger(HttpExporter.class)
@SuppressLogger(GrpcExporter.class)
class OtlpExporterAuthenticatorEndToEndTest {

  private static final String CLIENT_ID = "otel-exporter-client";
  private static final String CLIENT_SECRET = "otel-exporter-secret";
  private static final String EXPECTED_BASIC_AUTH =
      "Basic "
          + Base64.getEncoder()
              .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));

  private static final AtomicInteger tokenIssueCount = new AtomicInteger();
  private static final AtomicInteger remainingHttp503Count = new AtomicInteger();
  private static final AtomicInteger remainingGrpcUnavailableCount = new AtomicInteger();
  private static final Queue<RequestHeaders> recordedOtlpHeaders = new ConcurrentLinkedQueue<>();

  @RegisterExtension
  static final ServerExtension server =
      new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
          sb.http(0);

          // Local OAuth 2.0 Token Endpoint (RFC 6749 Client Credentials Grant)
          sb.service(
              "/oauth/token",
              (ctx, req) ->
                  HttpResponse.of(
                      req.aggregate()
                          .thenApply(
                              (AggregatedHttpRequest agg) -> {
                                String auth = agg.headers().get("authorization");
                                String body = agg.contentUtf8();
                                if (!EXPECTED_BASIC_AUTH.equals(auth)
                                    || !body.contains("grant_type=client_credentials")) {
                                  return HttpResponse.of(
                                      HttpStatus.UNAUTHORIZED,
                                      MediaType.JSON_UTF_8,
                                      "{\"error\":\"invalid_client\"}");
                                }
                                int issued = tokenIssueCount.incrementAndGet();
                                String tokenJson =
                                    "{\"access_token\":\"oauth2-token-"
                                        + issued
                                        + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
                                return HttpResponse.of(
                                    HttpStatus.OK, MediaType.JSON_UTF_8, tokenJson);
                              })));

          // Local OTLP/HTTP Collector Endpoints (/v1/traces, /v1/metrics, /v1/logs)
          sb.serviceUnder(
              "/v1/",
              (ctx, req) -> {
                recordedOtlpHeaders.add(req.headers());
                List<String> authValues = req.headers().getAll("authorization");
                int issued = tokenIssueCount.get();
                String expectedBearer = "Bearer oauth2-token-" + issued;
                if (authValues.size() != 1
                    || (!authValues.get(0).equals(expectedBearer)
                        && !authValues.get(0).startsWith("Bearer dynamic-token-"))) {
                  return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                }
                if (remainingHttp503Count.getAndDecrement() > 0) {
                  return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                }
                return HttpResponse.of(HttpStatus.OK);
              });

          // Local OTLP/gRPC Collector Endpoints (Traces, Metrics, Logs)
          HttpService grpcCollectorService =
              (ctx, req) -> {
                recordedOtlpHeaders.add(req.headers());
                List<String> authValues = req.headers().getAll("authorization");
                int issued = tokenIssueCount.get();
                String expectedBearer = "Bearer oauth2-token-" + issued;
                if (authValues.size() != 1
                    || (!authValues.get(0).equals(expectedBearer)
                        && !authValues.get(0).startsWith("Bearer dynamic-token-"))) {
                  return HttpResponse.of(
                      ResponseHeaders.builder(HttpStatus.OK)
                          .contentType(MediaType.parse("application/grpc+proto"))
                          .add("grpc-status", "16") // UNAUTHENTICATED
                          .build());
                }
                if (remainingGrpcUnavailableCount.getAndDecrement() > 0) {
                  return HttpResponse.of(
                      ResponseHeaders.builder(HttpStatus.OK)
                          .contentType(MediaType.parse("application/grpc+proto"))
                          .add("grpc-status", "14") // UNAVAILABLE
                          .build());
                }
                byte[] emptyGrpcFrame = {0, 0, 0, 0, 0};
                return HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.OK)
                        .contentType(MediaType.parse("application/grpc+proto"))
                        .add("grpc-status", "0")
                        .build(),
                    HttpData.wrap(emptyGrpcFrame));
              };
          sb.service(
              "/opentelemetry.proto.collector.trace.v1.TraceService/Export", grpcCollectorService);
          sb.service(
              "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export",
              grpcCollectorService);
          sb.service(
              "/opentelemetry.proto.collector.logs.v1.LogsService/Export", grpcCollectorService);
        }
      };

  @BeforeEach
  void resetState() {
    tokenIssueCount.set(0);
    remainingHttp503Count.set(0);
    remainingGrpcUnavailableCount.set(0);
    recordedOtlpHeaders.clear();
  }

  @Test
  void allSixOtlpExportersAuthenticateWithOAuth2EndToEnd() {
    OAuth2ClientCredentialsAuthenticator sharedOAuth2Authenticator =
        OAuth2ClientCredentialsAuthenticator.builder()
            .setTokenUrl(server.httpUri().resolve("/oauth/token"))
            .setClientId(CLIENT_ID)
            .setClientSecret(CLIENT_SECRET)
            .addScope("otel:export")
            .build();

    try (SpanExporter httpSpanExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpHttpSpanExporter.builder().setEndpoint(server.httpUri() + "/v1/traces"),
                    sharedOAuth2Authenticator)
                .build();
        SpanExporter grpcSpanExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpGrpcSpanExporter.builder().setEndpoint(server.httpUri().toString()),
                    sharedOAuth2Authenticator)
                .build();
        MetricExporter httpMetricExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpHttpMetricExporter.builder().setEndpoint(server.httpUri() + "/v1/metrics"),
                    sharedOAuth2Authenticator)
                .build();
        MetricExporter grpcMetricExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpGrpcMetricExporter.builder().setEndpoint(server.httpUri().toString()),
                    sharedOAuth2Authenticator)
                .build();
        LogRecordExporter httpLogExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpHttpLogRecordExporter.builder().setEndpoint(server.httpUri() + "/v1/logs"),
                    sharedOAuth2Authenticator)
                .build();
        LogRecordExporter grpcLogExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpGrpcLogRecordExporter.builder().setEndpoint(server.httpUri().toString()),
                    sharedOAuth2Authenticator)
                .build()) {

      assertThat(
              httpSpanExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              grpcSpanExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              httpMetricExporter
                  .export(Collections.singletonList(fakeMetricData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              grpcMetricExporter
                  .export(Collections.singletonList(fakeMetricData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              httpLogExporter
                  .export(Collections.singletonList(fakeLogRecordData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              grpcLogExporter
                  .export(Collections.singletonList(fakeLogRecordData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();

      // Token was fetched once from the OAuth2 server and reused across all 6 OTLP exports!
      assertThat(tokenIssueCount.get()).isEqualTo(1);
      assertThat(recordedOtlpHeaders).hasSize(6);
      for (RequestHeaders headers : recordedOtlpHeaders) {
        assertThat(headers.getAll("authorization")).containsExactly("Bearer oauth2-token-1");
      }
    }
  }

  @Test
  void unauthenticatedExportersAreRejectedByCollector() {
    try (SpanExporter httpSpanExporter =
            OtlpHttpSpanExporter.builder()
                .setEndpoint(server.httpUri() + "/v1/traces")
                .setRetryPolicy(null)
                .build();
        SpanExporter grpcSpanExporter =
            OtlpGrpcSpanExporter.builder()
                .setEndpoint(server.httpUri().toString())
                .setRetryPolicy(null)
                .build()) {
      assertThat(
              httpSpanExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isFalse();
      assertThat(
              grpcSpanExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isFalse();
    }
  }

  @Test
  void caseInsensitivePrecedenceOverStaticHeadersWithoutMutatingStaticHeaders() {
    AtomicInteger callNum = new AtomicInteger();
    Authenticator authenticator =
        () ->
            Collections.singletonMap(
                "Authorization", "Bearer dynamic-token-" + callNum.incrementAndGet());

    try (SpanExporter httpExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpHttpSpanExporter.builder()
                        .setEndpoint(server.httpUri() + "/v1/traces")
                        .addHeader("authorization", "Basic stale-static-header")
                        .addHeader("x-custom-static", "preserved-value")
                        .setHeaders(
                            () ->
                                Collections.singletonMap(
                                    "AUTHORIZATION", "Basic stale-supplier-header")),
                    authenticator)
                .build();
        SpanExporter grpcExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpGrpcSpanExporter.builder()
                        .setEndpoint(server.httpUri().toString())
                        .addHeader("AUTHORIZATION", "Basic stale-static-header")
                        .addHeader("x-custom-static", "preserved-value")
                        .setHeaders(
                            () ->
                                Collections.singletonMap(
                                    "authorization", "Basic stale-supplier-header")),
                    authenticator)
                .build()) {

      assertThat(
              httpExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              grpcExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();

      assertThat(recordedOtlpHeaders)
          .hasSize(2)
          .satisfiesExactly(
              req1 -> {
                assertThat(req1.getAll("authorization")).containsExactly("Bearer dynamic-token-1");
                assertThat(req1.get("x-custom-static")).isEqualTo("preserved-value");
              },
              req2 -> {
                assertThat(req2.getAll("authorization")).containsExactly("Bearer dynamic-token-2");
                assertThat(req2.get("x-custom-static")).isEqualTo("preserved-value");
              });
    }
  }

  @Test
  void authenticatorInvokedPerAttemptOnRetry() {
    AtomicInteger httpAuthCalls = new AtomicInteger();
    AtomicInteger grpcAuthCalls = new AtomicInteger();

    remainingHttp503Count.set(1);
    remainingGrpcUnavailableCount.set(1);

    RetryPolicy fastRetry =
        RetryPolicy.builder()
            .setMaxAttempts(3)
            .setInitialBackoff(Duration.ofMillis(10))
            .setMaxBackoff(Duration.ofMillis(50))
            .setBackoffMultiplier(1.5)
            .build();

    try (SpanExporter httpExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpHttpSpanExporter.builder()
                        .setEndpoint(server.httpUri() + "/v1/traces")
                        .setRetryPolicy(fastRetry),
                    () ->
                        Collections.singletonMap(
                            "Authorization",
                            "Bearer dynamic-token-http-" + httpAuthCalls.incrementAndGet()))
                .build();
        SpanExporter grpcExporter =
            OtlpExporterUtil.setAuthenticator(
                    OtlpGrpcSpanExporter.builder()
                        .setEndpoint(server.httpUri().toString())
                        .setRetryPolicy(fastRetry),
                    () ->
                        Collections.singletonMap(
                            "Authorization",
                            "Bearer dynamic-token-grpc-" + grpcAuthCalls.incrementAndGet()))
                .build()) {

      assertThat(
              httpExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(httpAuthCalls.get()).isEqualTo(2);

      assertThat(
              grpcExporter
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(grpcAuthCalls.get()).isEqualTo(2);
    }
  }

  @Test
  void authenticatorUnhandledExceptionFailsExportGracefullyAndShutdownPropagates() {
    AtomicBoolean httpAuthShutdown = new AtomicBoolean();
    AtomicBoolean grpcAuthShutdown = new AtomicBoolean();

    Authenticator failingHttpAuth =
        new Authenticator() {
          @Override
          public Map<String, String> getHeaders() {
            throw new IllegalStateException("Token server down");
          }

          @Override
          public CompletableResultCode shutdown() {
            httpAuthShutdown.set(true);
            return CompletableResultCode.ofSuccess();
          }
        };

    Authenticator failingGrpcAuth =
        new Authenticator() {
          @Override
          public Map<String, String> getHeaders() {
            throw new IllegalStateException("Token server down");
          }

          @Override
          public CompletableResultCode shutdown() {
            grpcAuthShutdown.set(true);
            return CompletableResultCode.ofSuccess();
          }
        };

    SpanExporter httpExporter =
        OtlpExporterUtil.setAuthenticator(
                OtlpHttpSpanExporter.builder()
                    .setEndpoint(server.httpUri() + "/v1/traces")
                    .setRetryPolicy(null),
                failingHttpAuth)
            .build();
    SpanExporter grpcExporter =
        OtlpExporterUtil.setAuthenticator(
                OtlpGrpcSpanExporter.builder()
                    .setEndpoint(server.httpUri().toString())
                    .setRetryPolicy(null),
                failingGrpcAuth)
            .build();

    // Ensure export() does not throw an unhandled exception, and instead returns a failed result
    CompletableResultCode httpResult =
        httpExporter.export(Collections.singletonList(fakeSpanData())).join(10, TimeUnit.SECONDS);
    assertThat(httpResult.isSuccess()).isFalse();

    CompletableResultCode grpcResult =
        grpcExporter.export(Collections.singletonList(fakeSpanData())).join(10, TimeUnit.SECONDS);
    assertThat(grpcResult.isSuccess()).isFalse();

    // Ensure toString() does NOT invoke getHeaders() (which would throw IllegalStateException)
    assertThat(httpExporter.toString()).contains("authenticator=");
    assertThat(grpcExporter.toString()).contains("authenticator=");

    // Ensure exporter.shutdown() invokes authenticator.shutdown()
    assertThat(httpExporter.shutdown().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
    assertThat(grpcExporter.shutdown().join(10, TimeUnit.SECONDS).isSuccess()).isTrue();
    assertThat(httpAuthShutdown.get()).isTrue();
    assertThat(grpcAuthShutdown.get()).isTrue();
  }

  @Test
  void toBuilderPreservesAuthenticator() {
    Authenticator authenticator =
        () -> Collections.singletonMap("Authorization", "Bearer dynamic-token-tobuilder");

    try (OtlpHttpSpanExporter originalHttp =
            OtlpExporterUtil.setAuthenticator(
                    OtlpHttpSpanExporter.builder().setEndpoint(server.httpUri() + "/v1/traces"),
                    authenticator)
                .build();
        OtlpHttpSpanExporter copiedHttp = originalHttp.toBuilder().build();
        OtlpGrpcSpanExporter originalGrpc =
            OtlpExporterUtil.setAuthenticator(
                    OtlpGrpcSpanExporter.builder().setEndpoint(server.httpUri().toString()),
                    authenticator)
                .build();
        OtlpGrpcSpanExporter copiedGrpc = originalGrpc.toBuilder().build()) {
      assertThat(
              copiedHttp
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(
              copiedGrpc
                  .export(Collections.singletonList(fakeSpanData()))
                  .join(10, TimeUnit.SECONDS)
                  .isSuccess())
          .isTrue();
      assertThat(recordedOtlpHeaders).hasSize(2);
      for (RequestHeaders headers : recordedOtlpHeaders) {
        assertThat(headers.getAll("authorization"))
            .containsExactly("Bearer dynamic-token-tobuilder");
      }
    }
  }

  private static SpanData fakeSpanData() {
    InstrumentationScopeInfo scope = InstrumentationScopeInfo.create("test");
    long now = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    return TestSpanData.builder()
        .setHasEnded(true)
        .setSpanContext(
            SpanContext.create(
                "00000000000000000000000000abc123",
                "0000000000def456",
                TraceFlags.getDefault(),
                TraceState.getDefault()))
        .setName("test-span")
        .setStartEpochNanos(now)
        .setEndEpochNanos(now + 1000)
        .setStatus(StatusData.ok())
        .setKind(SpanKind.INTERNAL)
        .setLinks(Collections.emptyList())
        .setTotalRecordedLinks(0)
        .setTotalRecordedEvents(0)
        .setInstrumentationScopeInfo(scope)
        .build();
  }

  private static MetricData fakeMetricData() {
    InstrumentationScopeInfo scope = InstrumentationScopeInfo.create("test");
    long now = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
    return ImmutableMetricData.createLongSum(
        Resource.empty(),
        scope,
        "test.metric",
        "desc",
        "1",
        ImmutableSumData.create(
            true,
            AggregationTemporality.CUMULATIVE,
            Collections.singletonList(
                ImmutableLongPointData.create(
                    now, now + 1000, Attributes.of(stringKey("k"), "v"), 1L))));
  }

  private static LogRecordData fakeLogRecordData() {
    return TestLogRecordData.builder()
        .setResource(Resource.getDefault())
        .setInstrumentationScopeInfo(InstrumentationScopeInfo.create("test"))
        .setBody("test log")
        .setSeverity(Severity.INFO)
        .setSeverityText("INFO")
        .setTimestamp(Instant.now())
        .setObservedTimestamp(Instant.now())
        .build();
  }
}
