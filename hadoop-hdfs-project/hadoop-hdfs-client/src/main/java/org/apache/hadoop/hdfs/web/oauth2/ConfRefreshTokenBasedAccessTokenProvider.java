/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.hadoop.hdfs.web.oauth2;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.web.URLConnectionFactory;
import org.apache.hadoop.util.JsonSerialization;
import org.apache.hadoop.util.Timer;
import org.apache.http.HttpStatus;

import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.OAUTH_CLIENT_ID_KEY;
import static org.apache.hadoop.hdfs.client.HdfsClientConfigKeys.OAUTH_REFRESH_URL_KEY;
import static org.apache.hadoop.hdfs.web.oauth2.OAuth2Constants.ACCESS_TOKEN;
import static org.apache.hadoop.hdfs.web.oauth2.OAuth2Constants.CLIENT_ID;
import static org.apache.hadoop.hdfs.web.oauth2.OAuth2Constants.EXPIRES_IN;
import static org.apache.hadoop.hdfs.web.oauth2.OAuth2Constants.GRANT_TYPE;
import static org.apache.hadoop.hdfs.web.oauth2.OAuth2Constants.REFRESH_TOKEN;
import static org.apache.hadoop.hdfs.web.oauth2.OAuth2Constants.URLENCODED;
import static org.apache.hadoop.hdfs.web.oauth2.Utils.notNull;

/**
 * Supply a access token obtained via a refresh token (provided through the
 * Configuration using the second half of the
 * <a href="https://tools.ietf.org/html/rfc6749#section-4.1">
 *   Authorization Code Grant workflow</a>.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public class ConfRefreshTokenBasedAccessTokenProvider
    extends AccessTokenProvider {

  public static final String OAUTH_REFRESH_TOKEN_KEY
      = "dfs.webhdfs.oauth2.refresh.token";
  public static final String OAUTH_REFRESH_TOKEN_EXPIRES_KEY
      = "dfs.webhdfs.oauth2.refresh.token.expires.ms.since.epoch";

  private AccessTokenTimer accessTokenTimer;

  private String accessToken;

  private String refreshToken;

  private String clientId;

  private String refreshURL;


  public ConfRefreshTokenBasedAccessTokenProvider() {
    this.accessTokenTimer = new AccessTokenTimer();
  }

  public ConfRefreshTokenBasedAccessTokenProvider(Timer timer) {
    this.accessTokenTimer = new AccessTokenTimer(timer);
  }

  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    refreshToken = notNull(conf, (OAUTH_REFRESH_TOKEN_KEY));

    accessTokenTimer.setExpiresInMSSinceEpoch(
        notNull(conf, OAUTH_REFRESH_TOKEN_EXPIRES_KEY));

    clientId = notNull(conf, OAUTH_CLIENT_ID_KEY);
    refreshURL = notNull(conf, OAUTH_REFRESH_URL_KEY);

  }

  @Override
  public synchronized String getAccessToken() throws IOException {
    if(accessTokenTimer.shouldRefresh()) {
      refresh();
    }

    return accessToken;
  }

  void refresh() throws IOException {
    OkHttpClient client =
        new OkHttpClient.Builder().connectTimeout(URLConnectionFactory.DEFAULT_SOCKET_TIMEOUT,
                TimeUnit.MILLISECONDS)
            .readTimeout(URLConnectionFactory.DEFAULT_SOCKET_TIMEOUT, TimeUnit.MILLISECONDS)
            .build();

    String bodyString =
        Utils.postBody(GRANT_TYPE, REFRESH_TOKEN, REFRESH_TOKEN, refreshToken, CLIENT_ID, clientId);

    RequestBody body = RequestBody.create(bodyString, URLENCODED);

    Request request = new Request.Builder().url(refreshURL).post(body).build();
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) {
        throw new IOException("Unexpected code " + response);
      }
      if (response.code() != HttpStatus.SC_OK) {
        throw new IllegalArgumentException(
            "Received invalid http response: " + response.code() + ", text = "
                + response.toString());
      }

      Map<?, ?> responseBody = JsonSerialization.mapReader().readValue(response.body().string());

      String newExpiresIn = responseBody.get(EXPIRES_IN).toString();
      accessTokenTimer.setExpiresIn(newExpiresIn);

      accessToken = responseBody.get(ACCESS_TOKEN).toString();
    } catch (Exception e) {
      throw new IOException("Exception while refreshing access token", e);
    }
  }

  public String getRefreshToken() {
    return refreshToken;
  }
}
