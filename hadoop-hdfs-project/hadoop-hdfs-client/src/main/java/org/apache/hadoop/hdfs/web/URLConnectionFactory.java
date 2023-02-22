/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hdfs.web;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.web.oauth2.OAuth2ConnectionConfigurator;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authentication.client.AuthenticatedURL;
import org.apache.hadoop.security.authentication.client.AuthenticationException;
import org.apache.hadoop.security.authentication.client.ConnectionConfigurator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.thirdparty.com.google.common.annotations.VisibleForTesting;

/**
 * Utilities for handling URLs
 */
@InterfaceAudience.LimitedPrivate({ "HDFS" })
@InterfaceStability.Unstable
public class URLConnectionFactory {
  private static final Logger LOG = LoggerFactory
      .getLogger(URLConnectionFactory.class);

  /**
   * Timeout for socket connects and reads
   */
  public final static int DEFAULT_SOCKET_TIMEOUT = 60 * 1000; // 1 minute
  private final ConnectionConfigurator connConfigurator;

  private static final ConnectionConfigurator DEFAULT_TIMEOUT_CONN_CONFIGURATOR
      = new ConnectionConfigurator() {
        @Override
        public HttpURLConnection configure(HttpURLConnection conn)
            throws IOException {
          URLConnectionFactory.setTimeouts(conn,
                                           DEFAULT_SOCKET_TIMEOUT,
                                           DEFAULT_SOCKET_TIMEOUT);
          return conn;
        }
      };

  /**
   * The URLConnectionFactory that sets the default timeout and it only trusts
   * Java's SSL certificates.
   */
  public static final URLConnectionFactory DEFAULT_SYSTEM_CONNECTION_FACTORY =
      new URLConnectionFactory(DEFAULT_TIMEOUT_CONN_CONFIGURATOR);

  /**
   * Construct a new URLConnectionFactory based on the configuration. It will
   * try to load SSL certificates when it is specified.
   */
  public static URLConnectionFactory newDefaultURLConnectionFactory(
      Configuration conf) {
    ConnectionConfigurator conn = getSSLConnectionConfiguration(
        DEFAULT_SOCKET_TIMEOUT, DEFAULT_SOCKET_TIMEOUT, conf);

    return new URLConnectionFactory(conn);
  }

  /**
   * Construct a new URLConnectionFactory based on the configuration. It will
   * hornor connecTimeout and readTimeout when they are specified.
   */
  public static URLConnectionFactory newDefaultURLConnectionFactory(
      int connectTimeout, int readTimeout, Configuration conf) {
    ConnectionConfigurator conn = getSSLConnectionConfiguration(
        connectTimeout, readTimeout, conf);
    return new URLConnectionFactory(conn);
  }

  private static ConnectionConfigurator getSSLConnectionConfiguration(
      final int connectTimeout, final int readTimeout, Configuration conf) {
    ConnectionConfigurator conn;
    try {
      conn = new SSLConnectionConfigurator(connectTimeout, readTimeout, conf);
    } catch (Exception e) {
      LOG.warn(
          "Cannot load customized ssl related configuration. Fallback to" +
              " system-generic settings.",
          e);
      if (connectTimeout == DEFAULT_SOCKET_TIMEOUT &&
          readTimeout == DEFAULT_SOCKET_TIMEOUT) {
        conn = DEFAULT_TIMEOUT_CONN_CONFIGURATOR;
      } else {
        conn = new ConnectionConfigurator() {
          @Override
          public HttpURLConnection configure(HttpURLConnection connection)
              throws IOException {
            URLConnectionFactory.setTimeouts(connection,
                connectTimeout,
                readTimeout);
            return connection;
          }
        };
      }
    }

    return conn;
  }

  /**
   * Construct a new URLConnectionFactory that supports OAut-based connections.
   * It will also try to load the SSL configuration when they are specified.
   */
  public static URLConnectionFactory newOAuth2URLConnectionFactory(
      int connectTimeout, int readTimeout, Configuration conf)
      throws IOException {
    ConnectionConfigurator conn;
    try {
      ConnectionConfigurator sslConnConfigurator
          = new SSLConnectionConfigurator(connectTimeout, readTimeout, conf);

      conn = new OAuth2ConnectionConfigurator(conf, sslConnConfigurator);
    } catch (Exception e) {
      throw new IOException("Unable to load OAuth2 connection factory.", e);
    }
    return new URLConnectionFactory(conn);
  }

  @VisibleForTesting
  URLConnectionFactory(ConnectionConfigurator connConfigurator) {
    this.connConfigurator = connConfigurator;
  }

  /**
   * Opens a url with read and connect timeouts
   *
   * @param url
   *          to open
   * @return URLConnection
   * @throws IOException
   */
  public URLConnection openConnection(URL url) throws IOException {
    try {
      return openConnection(url, false);
    } catch (AuthenticationException e) {
      // Unreachable
      LOG.error("Open connection {} failed", url, e);
      return null;
    }
  }

  /**
   * Opens a url with read and connect timeouts
   *
   * @param url
   *          URL to open
   * @param isSpnego
   *          whether the url should be authenticated via SPNEGO
   * @return URLConnection
   * @throws IOException
   * @throws AuthenticationException
   */
  public URLConnection openConnection(URL url, boolean isSpnego)
      throws IOException, AuthenticationException {
    if (isSpnego) {
      LOG.debug("open AuthenticatedURL connection {}", url);
      UserGroupInformation.getCurrentUser().checkTGTAndReloginFromKeytab();
      final AuthenticatedURL.Token authToken = new AuthenticatedURL.Token();
      return new AuthenticatedURL(new KerberosUgiAuthenticator(),
          connConfigurator).openConnection(url, authToken);
    } else {
      LOG.debug("open URL connection");
      URLConnection connection = url.openConnection();
      if (connection instanceof HttpURLConnection) {
        connConfigurator.configure((HttpURLConnection) connection);
      }
      return connection;
    }
  }

  /**
   * Sets timeout parameters on the given URLConnection.
   *
   * @param connection
   *          URLConnection to set
   * @param connectTimeout
   * @param readTimeout
   *          the connection and read timeout of the connection.
   */
  private static void setTimeouts(URLConnection connection,
                                  int connectTimeout,
                                  int readTimeout) {
    connection.setConnectTimeout(connectTimeout);
    connection.setReadTimeout(readTimeout);
  }

  public void destroy() {
    if (connConfigurator instanceof SSLConnectionConfigurator) {
      ((SSLConnectionConfigurator) connConfigurator).destroy();
    }
  }
}
