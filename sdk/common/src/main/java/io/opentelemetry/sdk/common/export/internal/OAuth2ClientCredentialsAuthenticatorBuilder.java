/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.common.export.internal;

import static java.util.Objects.requireNonNull;

import io.opentelemetry.sdk.common.Clock;
import io.opentelemetry.sdk.common.CompletableResultCode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;

/**
 * Builder for {@link OAuth2ClientCredentialsAuthenticator}.
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
public final class OAuth2ClientCredentialsAuthenticatorBuilder {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_REFRESH_MARGIN = Duration.ofSeconds(60);

  @Nullable private URI tokenUrl;
  @Nullable private String clientId;
  @Nullable private String clientSecret;
  private final List<String> scopes = new ArrayList<>();
  @Nullable private String audience;
  private Duration timeout = DEFAULT_TIMEOUT;
  private Duration refreshMargin = DEFAULT_REFRESH_MARGIN;
  @Nullable private SSLContext sslContext;
  private Clock clock = Clock.getDefault();

  OAuth2ClientCredentialsAuthenticatorBuilder() {}

  /**
   * Sets the OAuth 2.0 token endpoint URL.
   *
   * @param tokenUrl the token endpoint URL string
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setTokenUrl(String tokenUrl) {
    requireNonNull(tokenUrl, "tokenUrl");
    return setTokenUrl(URI.create(tokenUrl));
  }

  /**
   * Sets the OAuth 2.0 token endpoint URI.
   *
   * @param tokenUrl the token endpoint URI
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setTokenUrl(URI tokenUrl) {
    requireNonNull(tokenUrl, "tokenUrl");
    String scheme = tokenUrl.getScheme();
    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException(
          "Invalid tokenUrl scheme, must be http or https: " + tokenUrl);
    }
    this.tokenUrl = tokenUrl;
    return this;
  }

  /**
   * Sets the OAuth 2.0 client identifier.
   *
   * @param clientId the client ID
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setClientId(String clientId) {
    this.clientId = requireNonNull(clientId, "clientId");
    return this;
  }

  /**
   * Sets the OAuth 2.0 client secret.
   *
   * @param clientSecret the client secret
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setClientSecret(String clientSecret) {
    this.clientSecret = requireNonNull(clientSecret, "clientSecret");
    return this;
  }

  /**
   * Sets the OAuth 2.0 scopes to request.
   *
   * @param scopes the collection of scopes
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setScopes(Collection<String> scopes) {
    requireNonNull(scopes, "scopes");
    this.scopes.clear();
    for (String scope : scopes) {
      addScope(scope);
    }
    return this;
  }

  /**
   * Adds an OAuth 2.0 scope to request.
   *
   * @param scope the scope to add
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder addScope(String scope) {
    requireNonNull(scope, "scope");
    if (!scope.trim().isEmpty()) {
      this.scopes.add(scope.trim());
    }
    return this;
  }

  /**
   * Sets the optional OAuth 2.0 audience parameter.
   *
   * @param audience the target audience
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setAudience(@Nullable String audience) {
    this.audience = audience;
    return this;
  }

  /**
   * Sets the default timeout for token requests when no export timeout is provided.
   *
   * @param timeout the token request timeout
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setTimeout(Duration timeout) {
    requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.timeout = timeout;
    return this;
  }

  /**
   * Sets the proactive refresh margin before token expiration.
   *
   * @param refreshMargin the duration before expiry at which cached tokens should be refreshed
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setRefreshMargin(Duration refreshMargin) {
    requireNonNull(refreshMargin, "refreshMargin");
    if (refreshMargin.isNegative()) {
      throw new IllegalArgumentException("refreshMargin must not be negative");
    }
    this.refreshMargin = refreshMargin;
    return this;
  }

  /**
   * Sets a custom {@link SSLContext} for HTTPS token requests.
   *
   * @param sslContext the SSL context
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setSslContext(
      @Nullable SSLContext sslContext) {
    this.sslContext = sslContext;
    return this;
  }

  /**
   * Sets the {@link Clock} used to evaluate token expiration.
   *
   * @param clock the clock instance
   * @return this builder
   */
  public OAuth2ClientCredentialsAuthenticatorBuilder setClock(Clock clock) {
    this.clock = requireNonNull(clock, "clock");
    return this;
  }

  /**
   * Builds a new {@link OAuth2ClientCredentialsAuthenticator}.
   *
   * @return the configured authenticator
   */
  public OAuth2ClientCredentialsAuthenticator build() {
    if (tokenUrl == null) {
      throw new IllegalStateException("tokenUrl must be set");
    }
    if (clientId == null || clientId.isEmpty()) {
      throw new IllegalStateException("clientId must be set and non-empty");
    }
    if (clientSecret == null) {
      throw new IllegalStateException("clientSecret must be set");
    }
    return new OAuth2ClientCredentialsAuthenticator(
        tokenUrl,
        clientId,
        clientSecret,
        Collections.unmodifiableList(new ArrayList<>(scopes)),
        audience,
        timeout,
        refreshMargin,
        sslContext,
        clock,
        CompletableResultCode.ofSuccess());
  }
}
