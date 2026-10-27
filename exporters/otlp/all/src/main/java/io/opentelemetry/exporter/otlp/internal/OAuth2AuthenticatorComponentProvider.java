/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.exporter.otlp.internal;

import static io.opentelemetry.exporter.otlp.internal.OtlpConfigUtil.readFileBytes;

import io.opentelemetry.api.incubator.config.DeclarativeConfigException;
import io.opentelemetry.api.incubator.config.DeclarativeConfigProperties;
import io.opentelemetry.exporter.internal.TlsConfigHelper;
import io.opentelemetry.sdk.autoconfigure.spi.internal.ComponentProvider;
import io.opentelemetry.sdk.common.export.internal.Authenticator;
import io.opentelemetry.sdk.common.export.internal.OAuth2ClientCredentialsAuthenticator;
import io.opentelemetry.sdk.common.export.internal.OAuth2ClientCredentialsAuthenticatorBuilder;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * Declarative configuration {@link ComponentProvider} for {@link
 * OAuth2ClientCredentialsAuthenticator}.
 *
 * <p>This class is internal and is hence not for public use. Its APIs are unstable and can change
 * at any time.
 */
public final class OAuth2AuthenticatorComponentProvider implements ComponentProvider {

  private static final String NAME = "oauth2";

  @Override
  public Class<Authenticator> getType() {
    return Authenticator.class;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Authenticator create(DeclarativeConfigProperties config) {
    return createAuthenticator(config);
  }

  static Authenticator createAuthenticator(DeclarativeConfigProperties config) {
    String tokenUrl = config.getString("token_url");
    if (tokenUrl == null || tokenUrl.isEmpty()) {
      throw new DeclarativeConfigException("oauth2 authenticator requires 'token_url'");
    }

    String clientId = config.getString("client_id");
    if (clientId == null || clientId.isEmpty()) {
      throw new DeclarativeConfigException("oauth2 authenticator requires 'client_id'");
    }

    String clientSecret = config.getString("client_secret");
    if (clientSecret == null || clientSecret.isEmpty()) {
      throw new DeclarativeConfigException("oauth2 authenticator requires 'client_secret'");
    }

    try {
      OAuth2ClientCredentialsAuthenticatorBuilder builder =
          OAuth2ClientCredentialsAuthenticator.builder()
              .setTokenUrl(tokenUrl)
              .setClientId(clientId)
              .setClientSecret(clientSecret);

      List<String> scopes = config.getScalarList("scopes", String.class);
      if (scopes != null && !scopes.isEmpty()) {
        builder.setScopes(scopes);
      }

      String audience = config.getString("audience");
      if (audience != null && !audience.isEmpty()) {
        builder.setAudience(audience);
      }

      Integer timeoutMs = config.getInt("timeout");
      if (timeoutMs != null) {
        if (timeoutMs <= 0) {
          throw new DeclarativeConfigException("timeout must be positive: " + timeoutMs);
        }
        builder.setTimeout(Duration.ofMillis(timeoutMs));
      }

      Integer refreshMarginMs = config.getInt("refresh_margin");
      if (refreshMarginMs != null) {
        if (refreshMarginMs < 0) {
          throw new DeclarativeConfigException(
              "refresh_margin must not be negative: " + refreshMarginMs);
        }
        builder.setRefreshMargin(Duration.ofMillis(refreshMarginMs));
      }

      DeclarativeConfigProperties tls = config.getStructured("tls");
      if (tls != null) {
        TlsConfigHelper tlsConfigHelper = new TlsConfigHelper();
        String certificatePath = tls.getString("ca_file");
        String clientKeyPath = tls.getString("key_file");
        String clientKeyChainPath = tls.getString("cert_file");
        if (clientKeyPath != null && clientKeyChainPath == null) {
          throw new DeclarativeConfigException(
              "client_key_file provided without client_certificate_file - both client_key_file and client_certificate_file must be set");
        } else if (clientKeyPath == null && clientKeyChainPath != null) {
          throw new DeclarativeConfigException(
              "client_certificate_file provided without client_key_file - both client_key_file and client_certificate_file must be set");
        }
        byte[] certificateBytes = readFileBytes(certificatePath);
        if (certificateBytes != null) {
          tlsConfigHelper.setTrustManagerFromCerts(certificateBytes);
        }
        byte[] clientKeyBytes = readFileBytes(clientKeyPath);
        byte[] clientKeyChainBytes = readFileBytes(clientKeyChainPath);
        if (clientKeyBytes != null && clientKeyChainBytes != null) {
          tlsConfigHelper.setKeyManagerFromCerts(clientKeyBytes, clientKeyChainBytes);
        }
        SSLContext sslContext = tlsConfigHelper.getSslContext();
        if (sslContext != null) {
          builder.setSslContext(sslContext);
        }
      }

      return builder.build();
    } catch (DeclarativeConfigException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new DeclarativeConfigException("Failed to configure oauth2 authenticator", e);
    }
  }
}
