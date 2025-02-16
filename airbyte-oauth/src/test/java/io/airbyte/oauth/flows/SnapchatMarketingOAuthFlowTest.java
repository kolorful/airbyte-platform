/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.oauth.flows;

import io.airbyte.oauth.BaseOAuthFlow;
import java.util.List;

@SuppressWarnings("MissingJavadocType")
public class SnapchatMarketingOAuthFlowTest extends BaseOAuthFlowTest {

  @Override
  protected BaseOAuthFlow getOAuthFlow() {
    return new SnapchatMarketingOAuthFlow(getHttpClient(), this::getConstantState);
  }

  @Override
  protected List<String> getExpectedOutputPath() {
    return List.of();
  }

  @Override
  protected String getExpectedConsentUrl() {
    return "https://accounts.snapchat.com/login/oauth2/authorize?client_id=test_client_id&redirect_uri=https%3A%2F%2Fairbyte.io&response_type=code&scope=snapchat-marketing-api&state=state";
  }

}
