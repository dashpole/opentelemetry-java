/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.common.export.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.sdk.testing.time.TestClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OAuth2ClientCredentialsAuthenticatorTest {

  private HttpServer server;
  private URI tokenUri;
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicReference<String> lastAuthHeader = new AtomicReference<>();
  private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
  private final AtomicInteger responseStatus = new AtomicInteger(200);
  private final AtomicReference<String> responseJson = new AtomicReference<>();

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/oauth/token",
        exchange -> {
          int count = tokenRequests.incrementAndGet();
          lastAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
          lastRequestBody.set(readBody(exchange.getRequestBody()));
          int status = responseStatus.get();
          String body = responseJson.get();
          if (body == null) {
            body =
                "{\"access_token\":\"secret-token-"
                    + count
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
          }
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, bytes.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
          }
        });
    server.start();
    tokenUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/oauth/token");
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void tokenAcquisitionCachingAndRefresh() {
    TestClock clock = TestClock.create();
    try (OAuth2ClientCredentialsAuthenticator authenticator =
        OAuth2ClientCredentialsAuthenticator.builder()
            .setTokenUrl(tokenUri)
            .setClientId("my-client")
            .setClientSecret("my-secret")
            .setScopes(Arrays.asList("otel.traces", "otel.metrics"))
            .setAudience("https://collector.example.com")
            .setRefreshMargin(Duration.ofSeconds(60))
            .setClock(clock)
            .build()) {

      Map<String, String> headers1 = authenticator.getHeaders();
      assertThat(headers1).containsEntry("Authorization", "Bearer secret-token-1");
      assertThat(tokenRequests.get()).isEqualTo(1);
      String expectedBasic =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("my-client:my-secret".getBytes(StandardCharsets.UTF_8));
      assertThat(lastAuthHeader.get()).isEqualTo(expectedBasic);
      assertThat(lastRequestBody.get())
          .isEqualTo(
              "grant_type=client_credentials&scope=otel.traces+otel.metrics&audience=https%3A%2F%2Fcollector.example.com");

      // Advance clock by 30 minutes (still well before 3600 - 60s expiry): cached token reused
      clock.advance(Duration.ofMinutes(30));
      Map<String, String> headers2 = authenticator.getHeaders();
      assertThat(headers2).containsEntry("Authorization", "Bearer secret-token-1");
      assertThat(tokenRequests.get()).isEqualTo(1);

      // Advance clock past refresh margin (total 59m01s >= 3540s): refreshes token automatically
      clock.advance(Duration.ofMinutes(29).plusSeconds(1));
      Map<String, String> headers3 = authenticator.getHeaders();
      assertThat(headers3).containsEntry("Authorization", "Bearer secret-token-2");
      assertThat(tokenRequests.get()).isEqualTo(2);
    }
  }

  @Test
  void httpErrorAndMalformedJsonDoNotLeakSecrets() {
    OAuth2ClientCredentialsAuthenticator authenticator =
        OAuth2ClientCredentialsAuthenticator.builder()
            .setTokenUrl(tokenUri.toString())
            .setClientId("client-id")
            .setClientSecret("top-secret-password")
            .build();

    responseStatus.set(401);
    responseJson.set("{\"error\":\"invalid_client\",\"secret\":\"top-secret-password\"}");
    assertThatThrownBy(authenticator::getHeaders)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP status 401")
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("top-secret-password"));

    responseStatus.set(200);
    responseJson.set("{\"not_access_token\":\" sensitive-raw-body \"}");
    assertThatThrownBy(authenticator::getHeaders)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing non-empty 'access_token'")
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("sensitive-raw-body"));
  }

  @Test
  void toStringRedactsSecretAndTokens() {
    OAuth2ClientCredentialsAuthenticator authenticator =
        OAuth2ClientCredentialsAuthenticator.builder()
            .setTokenUrl(tokenUri)
            .setClientId("my-client-id")
            .setClientSecret("super-secret-value")
            .addScope("otel:write")
            .build();

    assertThat(authenticator.getHeaders()).containsEntry("Authorization", "Bearer secret-token-1");
    assertThat(authenticator.toString())
        .contains("clientId=my-client-id")
        .contains("clientSecret=OBFUSCATED")
        .doesNotContain("super-secret-value")
        .doesNotContain("secret-token-1");

    assertThat(authenticator.shutdown().isSuccess()).isTrue();
    assertThatThrownBy(authenticator::getHeaders)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shut down");
  }

  private static String readBody(InputStream is) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[1024];
    int n;
    while ((n = is.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, n);
    }
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }
}
