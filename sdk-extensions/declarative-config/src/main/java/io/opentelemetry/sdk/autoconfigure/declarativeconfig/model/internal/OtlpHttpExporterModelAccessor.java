/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.internal;

import static java.util.Objects.requireNonNull;

import io.opentelemetry.sdk.autoconfigure.declarativeconfig.model.OtlpHttpExporterModel;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Provides typed access to experimental properties on {@link OtlpHttpExporterModel}.
 *
 * <p>This class is internal and experimental. Its APIs are unstable and can change at any time. Its
 * APIs (or a version of them) may be promoted to the public stable API in the future, but no
 * guarantees are made.
 */
public final class OtlpHttpExporterModelAccessor {

  private OtlpHttpExporterModelAccessor() {}

  static final String AUTHENTICATOR = "authenticator/development";

  public static final Map<String, Class<?>> EXPERIMENTAL_PROPERTIES;

  static {
    EXPERIMENTAL_PROPERTIES = new HashMap<>();
    EXPERIMENTAL_PROPERTIES.put(AUTHENTICATOR, ExperimentalAuthenticatorModel.class);
  }

  @Nullable
  public static ExperimentalAuthenticatorModel getAuthenticator(OtlpHttpExporterModel model) {
    return ExtensionPropertyUtil.get(
        AUTHENTICATOR, model.getExtensionProperties(), ExperimentalAuthenticatorModel.class);
  }

  public static OtlpHttpExporterModel setAuthenticator(
      OtlpHttpExporterModel model, ExperimentalAuthenticatorModel value) {
    requireNonNull(value, "value");
    model.setExtensionProperty(AUTHENTICATOR, value);
    return model;
  }
}
