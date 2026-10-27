/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure.declarativeconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.opentelemetry.api.incubator.config.DeclarativeConfigException;
import io.opentelemetry.api.logs.Logger;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.internal.testing.CleanupExtension;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * End-to-end declarative configuration YAML tests verifying {@code authenticator/development} with
 * {@code oauth2} across all 6 OTLP exporters.
 */
class OtlpExporterAuthenticatorDeclarativeConfigTest {

  private static final AtomicInteger tokenIssueCount = new AtomicInteger();
  private static final Queue<RequestHeaders> recordedHttpRequests = new ConcurrentLinkedQueue<>();
  private static final Queue<RequestHeaders> recordedGrpcRequests = new ConcurrentLinkedQueue<>();

  @RegisterExtension CleanupExtension cleanup = new CleanupExtension();

  @RegisterExtension
  static final ServerExtension server =
      new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
          sb.http(0);

          sb.service(
              "/oauth/token",
              (ctx, req) ->
                  HttpResponse.of(
                      req.aggregate()
                          .thenApply(
                              (AggregatedHttpRequest agg) -> {
                                int issued = tokenIssueCount.incrementAndGet();
                                return HttpResponse.of(
                                    HttpStatus.OK,
                                    MediaType.JSON_UTF_8,
                                    "{\"access_token\":\"yaml-token-"
                                        + issued
                                        + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                              })));

          sb.serviceUnder(
              "/v1/",
              (ctx, req) -> {
                recordedHttpRequests.add(req.headers());
                List<String> auth = req.headers().getAll("authorization");
                if (auth.size() != 1 || !auth.get(0).startsWith("Bearer yaml-token-")) {
                  return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                }
                return HttpResponse.of(HttpStatus.OK);
              });

          HttpService grpcCollectorService =
              (ctx, req) -> {
                recordedGrpcRequests.add(req.headers());
                List<String> auth = req.headers().getAll("authorization");
                if (auth.size() != 1 || !auth.get(0).startsWith("Bearer yaml-token-")) {
                  return HttpResponse.of(
                      ResponseHeaders.builder(HttpStatus.OK)
                          .contentType(MediaType.parse("application/grpc+proto"))
                          .add("grpc-status", "16")
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
  void reset() {
    tokenIssueCount.set(0);
    recordedHttpRequests.clear();
    recordedGrpcRequests.clear();
  }

  @Test
  void parseAndCreateAllSixOtlpExportersWithOAuth2Authenticator() {
    String tokenUrl = server.httpUri() + "/oauth/token";
    String httpBase = server.httpUri().toString();
    String yaml =
        "file_format: \"1.0\"\n"
            + "tracer_provider:\n"
            + "  processors:\n"
            + "    - simple:\n"
            + "        exporter:\n"
            + "          otlp_http:\n"
            + "            endpoint: "
            + httpBase
            + "/v1/traces\n"
            + "            authenticator/development:\n"
            + "              oauth2:\n"
            + "                token_url: "
            + tokenUrl
            + "\n"
            + "                client_id: yaml-client\n"
            + "                client_secret: yaml-secret\n"
            + "                scopes:\n"
            + "                  - otel:traces\n"
            + "    - simple:\n"
            + "        exporter:\n"
            + "          otlp_grpc:\n"
            + "            endpoint: "
            + httpBase
            + "\n"
            + "            authenticator/development:\n"
            + "              oauth2:\n"
            + "                token_url: "
            + tokenUrl
            + "\n"
            + "                client_id: yaml-client\n"
            + "                client_secret: yaml-secret\n"
            + "                scopes:\n"
            + "                  - otel:traces\n"
            + "                  - otel:write\n"
            + "meter_provider:\n"
            + "  readers:\n"
            + "    - periodic:\n"
            + "        interval: 60000\n"
            + "        exporter:\n"
            + "          otlp_http:\n"
            + "            endpoint: "
            + httpBase
            + "/v1/metrics\n"
            + "            authenticator/development:\n"
            + "              oauth2:\n"
            + "                token_url: "
            + tokenUrl
            + "\n"
            + "                client_id: yaml-client\n"
            + "                client_secret: yaml-secret\n"
            + "                scopes:\n"
            + "                  - otel:metrics\n"
            + "    - periodic:\n"
            + "        interval: 60000\n"
            + "        exporter:\n"
            + "          otlp_grpc:\n"
            + "            endpoint: "
            + httpBase
            + "\n"
            + "            authenticator/development:\n"
            + "              oauth2:\n"
            + "                token_url: "
            + tokenUrl
            + "\n"
            + "                client_id: yaml-client\n"
            + "                client_secret: yaml-secret\n"
            + "logger_provider:\n"
            + "  processors:\n"
            + "    - simple:\n"
            + "        exporter:\n"
            + "          otlp_http:\n"
            + "            endpoint: "
            + httpBase
            + "/v1/logs\n"
            + "            authenticator/development:\n"
            + "              oauth2:\n"
            + "                token_url: "
            + tokenUrl
            + "\n"
            + "                client_id: yaml-client\n"
            + "                client_secret: yaml-secret\n"
            + "    - simple:\n"
            + "        exporter:\n"
            + "          otlp_grpc:\n"
            + "            endpoint: "
            + httpBase
            + "\n"
            + "            authenticator/development:\n"
            + "              oauth2:\n"
            + "                token_url: "
            + tokenUrl
            + "\n"
            + "                client_id: yaml-client\n"
            + "                client_secret: yaml-secret\n";

    OpenTelemetrySdk sdk =
        DeclarativeConfiguration.parseAndCreate(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)))
            .getSdk();
    cleanup.addCloseable(sdk);

    Span span = sdk.getTracer("test").spanBuilder("span-1").startSpan();
    span.end();

    LongCounter counter = sdk.getMeter("test").counterBuilder("counter-1").build();
    counter.add(1);

    Logger logger = sdk.getLogsBridge().get("test");
    logger.logRecordBuilder().setBody("log-1").emit();

    assertThat(sdk.getSdkTracerProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess())
        .isTrue();
    assertThat(sdk.getSdkMeterProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess())
        .isTrue();
    assertThat(sdk.getSdkLoggerProvider().forceFlush().join(10, TimeUnit.SECONDS).isSuccess())
        .isTrue();

    assertThat(recordedHttpRequests).hasSize(3);
    assertThat(recordedGrpcRequests).hasSize(3);
  }

  @Test
  void invalidAuthenticatorNameThrowsDeclarativeConfigException() {
    String yaml =
        "file_format: \"1.0\"\n"
            + "tracer_provider:\n"
            + "  processors:\n"
            + "    - simple:\n"
            + "        exporter:\n"
            + "          otlp_http:\n"
            + "            authenticator/development:\n"
            + "              nonexistent_authenticator: {}\n";

    assertThatThrownBy(
            () ->
                DeclarativeConfiguration.parseAndCreate(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(DeclarativeConfigException.class)
        .hasRootCauseMessage(
            "No component provider detected for io.opentelemetry.sdk.common.export.internal.Authenticator with name \"nonexistent_authenticator\".");
  }
}
