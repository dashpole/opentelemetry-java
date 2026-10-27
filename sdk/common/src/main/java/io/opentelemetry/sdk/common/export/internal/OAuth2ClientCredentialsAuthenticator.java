/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.common.export.internal;

import io.opentelemetry.api.impl.InstrumentationUtil;
import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

/**
 * An {@link Authenticator} that acquires and automatically refreshes OAuth 2.0 Bearer tokens using
 * the RFC 6749 Client Credentials Grant ({@code grant_type=client_credentials}).
 *
 * <p>Instances of this class are thread-safe and cache acquired access tokens in memory until
 * {@code expires_in - refreshMargin} is reached.
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
@ThreadSafe
public final class OAuth2ClientCredentialsAuthenticator implements Authenticator {

  private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600L;

  private final URI tokenUrl;
  private final String clientId;
  private final String basicAuthHeaderValue;
  private final String formBody;
  private final List<String> scopes;
  @Nullable private final String audience;
  private final Duration defaultTimeout;
  private final long refreshMarginNanos;
  @Nullable private final SSLContext sslContext;
  private final Clock clock;
  private final CompletableResultCode shutdownResult;

  private final Object lock = new Object();
  private final AtomicBoolean isShutdown = new AtomicBoolean(false);
  @Nullable private volatile CachedToken cachedToken;

  OAuth2ClientCredentialsAuthenticator(
      URI tokenUrl,
      String clientId,
      String clientSecret,
      List<String> scopes,
      @Nullable String audience,
      Duration defaultTimeout,
      Duration refreshMargin,
      @Nullable SSLContext sslContext,
      Clock clock,
      CompletableResultCode shutdownResult) {
    this.tokenUrl = tokenUrl;
    this.clientId = clientId;
    this.scopes = scopes;
    this.audience = audience;
    this.defaultTimeout = defaultTimeout;
    this.refreshMarginNanos = refreshMargin.toNanos();
    this.sslContext = sslContext;
    this.clock = clock;
    this.shutdownResult = shutdownResult;

    // RFC 6749 Section 2.3.1: client_id and client_secret are application/x-www-form-urlencoded
    // before Base64 encoding for HTTP Basic authentication.
    String credentials = urlEncode(clientId) + ":" + urlEncode(clientSecret);
    this.basicAuthHeaderValue =
        "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

    StringBuilder bodyBuilder = new StringBuilder("grant_type=client_credentials");
    if (!scopes.isEmpty()) {
      StringBuilder joinedScopes = new StringBuilder();
      for (int i = 0; i < scopes.size(); i++) {
        if (i > 0) {
          joinedScopes.append(' ');
        }
        joinedScopes.append(scopes.get(i));
      }
      bodyBuilder.append("&scope=").append(urlEncode(joinedScopes.toString()));
    }
    if (audience != null && !audience.trim().isEmpty()) {
      bodyBuilder.append("&audience=").append(urlEncode(audience.trim()));
    }
    this.formBody = bodyBuilder.toString();
  }

  /**
   * Returns a new {@link OAuth2ClientCredentialsAuthenticatorBuilder} to configure an {@link
   * OAuth2ClientCredentialsAuthenticator}.
   *
   * @return a new builder instance
   */
  public static OAuth2ClientCredentialsAuthenticatorBuilder builder() {
    return new OAuth2ClientCredentialsAuthenticatorBuilder();
  }

  @Override
  public Map<String, String> getHeaders() {
    if (isShutdown.get()) {
      throw new IllegalStateException("OAuth2ClientCredentialsAuthenticator is shut down");
    }

    CachedToken current = this.cachedToken;
    if (current != null && !current.isExpired(clock.nanoTime())) {
      return Collections.singletonMap("Authorization", current.authorizationHeaderValue);
    }

    synchronized (lock) {
      if (isShutdown.get()) {
        throw new IllegalStateException("OAuth2ClientCredentialsAuthenticator is shut down");
      }
      current = this.cachedToken;
      if (current != null && !current.isExpired(clock.nanoTime())) {
        return Collections.singletonMap("Authorization", current.authorizationHeaderValue);
      }

      CachedToken[] refreshedHolder = new CachedToken[1];
      InstrumentationUtil.suppressInstrumentation(
          () -> refreshedHolder[0] = fetchToken(defaultTimeout));
      CachedToken refreshed = refreshedHolder[0];
      this.cachedToken = refreshed;
      return Collections.singletonMap("Authorization", refreshed.authorizationHeaderValue);
    }
  }

  private CachedToken fetchToken(Duration timeout) {
    int timeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.toMillis()));
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) tokenUrl.toURL().openConnection();
      if (sslContext != null && connection instanceof HttpsURLConnection) {
        ((HttpsURLConnection) connection).setSSLSocketFactory(sslContext.getSocketFactory());
      }
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);
      connection.setDoOutput(true);
      connection.setUseCaches(false);
      connection.setRequestProperty("Authorization", basicAuthHeaderValue);
      connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
      connection.setRequestProperty("Accept", "application/json");

      byte[] bodyBytes = formBody.getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(bodyBytes.length);
      try (OutputStream os = connection.getOutputStream()) {
        os.write(bodyBytes);
        os.flush();
      }

      int statusCode = connection.getResponseCode();
      if (statusCode < 200 || statusCode >= 300) {
        drainQuietly(connection.getErrorStream());
        throw new IllegalStateException(
            "OAuth2 token request to " + tokenUrl + " failed with HTTP status " + statusCode);
      }

      String responseBody;
      try (InputStream is = connection.getInputStream()) {
        responseBody = readString(is);
      }

      return parseTokenResponse(responseBody);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to fetch OAuth2 token from " + tokenUrl, e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private CachedToken parseTokenResponse(String json) {
    Map<String, String> fields = parseFlatJsonFields(json);
    String accessToken = fields.get("access_token");
    if (accessToken == null || accessToken.isEmpty()) {
      throw new IllegalStateException(
          "OAuth2 token response missing non-empty 'access_token' field");
    }

    String tokenType = fields.get("token_type");
    if (tokenType == null || tokenType.isEmpty() || tokenType.equalsIgnoreCase("bearer")) {
      tokenType = "Bearer";
    }

    long expiresInSeconds = DEFAULT_EXPIRES_IN_SECONDS;
    String expiresInStr = fields.get("expires_in");
    if (expiresInStr != null && !expiresInStr.isEmpty()) {
      try {
        double parsed = Double.parseDouble(expiresInStr);
        if (parsed > 0) {
          expiresInSeconds = Math.max(1L, (long) parsed);
        }
      } catch (NumberFormatException e) {
        throw new IllegalStateException("OAuth2 token response has invalid 'expires_in' value", e);
      }
    }

    long expiresInNanos = TimeUnit.SECONDS.toNanos(expiresInSeconds);
    long effectiveMarginNanos = Math.min(refreshMarginNanos, expiresInNanos / 2);
    long expiresAtNanoTime = clock.nanoTime() + expiresInNanos - effectiveMarginNanos;
    return new CachedToken(tokenType + " " + accessToken, expiresAtNanoTime);
  }

  /**
   * Minimal RFC 8259 compliant top-level JSON object parser that extracts primitive string and
   * numeric fields without external dependencies and without echoing raw token payloads in errors.
   */
  static Map<String, String> parseFlatJsonFields(String json) {
    if (json == null) {
      throw new IllegalStateException("OAuth2 token response body is null");
    }
    int len = json.length();
    int idx = skipWhitespace(json, 0, len);
    if (idx >= len || json.charAt(idx) != '{') {
      throw new IllegalStateException("OAuth2 token response is not a JSON object");
    }
    idx++;

    Map<String, String> result = new HashMap<>();
    idx = skipWhitespace(json, idx, len);
    if (idx < len && json.charAt(idx) == '}') {
      return result;
    }

    while (idx < len) {
      idx = skipWhitespace(json, idx, len);
      if (idx >= len || json.charAt(idx) != '"') {
        throw new IllegalStateException("Malformed JSON key in OAuth2 token response");
      }
      ParsedString key = parseJsonString(json, idx, len);
      idx = skipWhitespace(json, key.nextIndex, len);
      if (idx >= len || json.charAt(idx) != ':') {
        throw new IllegalStateException("Expected ':' after key in OAuth2 token response");
      }
      idx = skipWhitespace(json, idx + 1, len);
      if (idx >= len) {
        throw new IllegalStateException("Unexpected end of JSON in OAuth2 token response");
      }

      char c = json.charAt(idx);
      if (c == '"') {
        ParsedString val = parseJsonString(json, idx, len);
        result.put(key.value, val.value);
        idx = val.nextIndex;
      } else if (c == '{' || c == '[') {
        idx = skipNestedStructure(json, idx, len);
      } else {
        int valStart = idx;
        while (idx < len) {
          char vc = json.charAt(idx);
          if (vc == ',' || vc == '}' || Character.isWhitespace(vc)) {
            break;
          }
          idx++;
        }
        String rawVal = json.substring(valStart, idx);
        if (!"null".equals(rawVal)) {
          result.put(key.value, rawVal);
        }
      }

      idx = skipWhitespace(json, idx, len);
      if (idx < len && json.charAt(idx) == ',') {
        idx++;
      } else if (idx < len && json.charAt(idx) == '}') {
        return result;
      } else {
        throw new IllegalStateException("Malformed JSON object in OAuth2 token response");
      }
    }
    throw new IllegalStateException("Unterminated JSON object in OAuth2 token response");
  }

  private static int skipWhitespace(String s, int idx, int len) {
    while (idx < len && Character.isWhitespace(s.charAt(idx))) {
      idx++;
    }
    return idx;
  }

  private static ParsedString parseJsonString(String s, int startQuoteIdx, int len) {
    StringBuilder sb = new StringBuilder();
    int idx = startQuoteIdx + 1;
    while (idx < len) {
      char c = s.charAt(idx++);
      if (c == '"') {
        return new ParsedString(sb.toString(), idx);
      }
      if (c == '\\') {
        if (idx >= len) {
          throw new IllegalStateException("Unterminated string escape in OAuth2 token response");
        }
        char esc = s.charAt(idx++);
        switch (esc) {
          case '"':
          case '\\':
          case '/':
            sb.append(esc);
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'u':
            if (idx + 4 > len) {
              throw new IllegalStateException("Invalid unicode escape in OAuth2 token response");
            }
            String hex = s.substring(idx, idx + 4);
            try {
              sb.append((char) Integer.parseInt(hex, 16));
            } catch (NumberFormatException e) {
              throw new IllegalStateException("Invalid unicode escape in OAuth2 token response", e);
            }
            idx += 4;
            break;
          default:
            throw new IllegalStateException("Invalid escape character in OAuth2 token response");
        }
      } else {
        sb.append(c);
      }
    }
    throw new IllegalStateException("Unterminated JSON string in OAuth2 token response");
  }

  private static int skipNestedStructure(String s, int startIdx, int len) {
    int depth = 0;
    int idx = startIdx;
    while (idx < len) {
      char c = s.charAt(idx);
      if (c == '"') {
        idx = parseJsonString(s, idx, len).nextIndex;
        continue;
      }
      if (c == '{' || c == '[') {
        depth++;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth == 0) {
          return idx + 1;
        }
      }
      idx++;
    }
    throw new IllegalStateException("Unterminated nested JSON value in OAuth2 token response");
  }

  private static String readString(InputStream is) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] data = new byte[4096];
    int bytesRead;
    while ((bytesRead = is.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, bytesRead);
    }
    return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
  }

  private static void drainQuietly(@Nullable InputStream is) {
    if (is == null) {
      return;
    }
    try {
      byte[] buf = new byte[1024];
      while (is.read(buf) != -1) {
        // discard
      }
    } catch (IOException ignored) {
      // ignore
    } finally {
      try {
        is.close();
      } catch (IOException ignored) {
        // ignore
      }
    }
  }

  @SuppressWarnings("JdkObsolete") // Recommended alternative was introduced in java 10
  private static String urlEncode(String value) {
    try {
      return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 encoding not supported", e);
    }
  }

  @Override
  public CompletableResultCode shutdown() {
    if (isShutdown.compareAndSet(false, true)) {
      synchronized (lock) {
        this.cachedToken = null;
      }
    }
    return shutdownResult;
  }

  @Override
  public String toString() {
    return "OAuth2ClientCredentialsAuthenticator{"
        + "tokenUrl="
        + tokenUrl
        + ", clientId="
        + clientId
        + ", clientSecret=OBFUSCATED"
        + ", scopes="
        + scopes
        + (audience != null ? ", audience=" + audience : "")
        + "}";
  }

  private static final class CachedToken {
    final String authorizationHeaderValue;
    final long expiresAtNanoTime;

    CachedToken(String authorizationHeaderValue, long expiresAtNanoTime) {
      this.authorizationHeaderValue = authorizationHeaderValue;
      this.expiresAtNanoTime = expiresAtNanoTime;
    }

    boolean isExpired(long currentNanoTime) {
      return currentNanoTime - expiresAtNanoTime >= 0;
    }
  }

  private static final class ParsedString {
    final String value;
    final int nextIndex;

    ParsedString(String value, int nextIndex) {
      this.value = value;
      this.nextIndex = nextIndex;
    }
  }
}
