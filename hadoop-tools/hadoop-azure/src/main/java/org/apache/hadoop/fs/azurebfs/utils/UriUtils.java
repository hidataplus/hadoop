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

package org.apache.hadoop.fs.azurebfs.utils;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.AND_MARK;
import static org.apache.hadoop.fs.azurebfs.constants.AbfsHttpConstants.EQUAL;
import static org.apache.hadoop.fs.azurebfs.constants.HttpQueryParams.QUERY_PARAM_SAOID;
import static org.apache.hadoop.fs.azurebfs.constants.HttpQueryParams.QUERY_PARAM_SIGNATURE;
import static org.apache.hadoop.fs.azurebfs.constants.HttpQueryParams.QUERY_PARAM_SKOID;
import static org.apache.hadoop.fs.azurebfs.constants.HttpQueryParams.QUERY_PARAM_SUOID;

/**
 * Utility class to help with Abfs url transformation to blob urls.
 */
public final class UriUtils {
  private static final String ABFS_URI_REGEX = "[^.]+\\.dfs\\.(preprod\\.){0,1}core\\.windows\\.net";
  private static final Pattern ABFS_URI_PATTERN = Pattern.compile(ABFS_URI_REGEX);
  private static final Set<String> FULL_MASK_PARAM_KEYS = new HashSet<>(
      Collections.singleton(QUERY_PARAM_SIGNATURE));
  private static final Set<String> PARTIAL_MASK_PARAM_KEYS = new HashSet<>(
      Arrays.asList(QUERY_PARAM_SKOID, QUERY_PARAM_SAOID, QUERY_PARAM_SUOID));
  private static final Character CHAR_MASK = 'X';
  private static final String FULL_MASK = "XXXXX";
  private static final int DEFAULT_QUERY_STRINGBUILDER_CAPACITY = 550;
  private static final int PARTIAL_MASK_VISIBLE_LEN = 18;

  /**
   * Checks whether a string includes abfs url.
   * @param string the string to check.
   * @return true if string has abfs url.
   */
  public static boolean containsAbfsUrl(final String string) {
    if (string == null || string.isEmpty()) {
      return false;
    }

    return ABFS_URI_PATTERN.matcher(string).matches();
  }

  /**
   * Extracts the account name from the host name.
   * @param hostName the fully-qualified domain name of the storage service
   *                 endpoint (e.g. {account}.dfs.core.windows.net.
   * @return the storage service account name.
   */
  public static String extractAccountNameFromHostName(final String hostName) {
    if (hostName == null || hostName.isEmpty()) {
      return null;
    }

    if (!containsAbfsUrl(hostName)) {
      return null;
    }

    String[] splitByDot = hostName.split("\\.");
    if (splitByDot.length == 0) {
      return null;
    }

    return splitByDot[0];
  }

  /**
   * Generate unique test path for multiple user tests.
   *
   * @return root test path
   */
  public static String generateUniqueTestPath() {
    String testUniqueForkId = System.getProperty("test.unique.fork.id");
    return testUniqueForkId == null ? "/test" : "/" + testUniqueForkId + "/test";
  }

  public static String maskUrlQueryParameters(List<NameValuePair> keyValueList,
      Set<String> queryParamsForFullMask,
      Set<String> queryParamsForPartialMask) {
    return maskUrlQueryParameters(keyValueList, queryParamsForFullMask,
        queryParamsForPartialMask, DEFAULT_QUERY_STRINGBUILDER_CAPACITY);
  }

  /**
   * Generic function to mask a set of query parameters partially/fully and
   * return the resultant query string
   * @param keyValueList List of NameValuePair instances for query keys/values
   * @param queryParamsForFullMask values for these params will appear as "XXXX"
   * @param queryParamsForPartialMask values will be masked with 'X', except for
   *                                  the last PARTIAL_MASK_VISIBLE_LEN characters
   * @param queryLen to initialize StringBuilder for the masked query
   * @return the masked url query part
   */
  public static String maskUrlQueryParameters(List<NameValuePair> keyValueList,
      Set<String> queryParamsForFullMask,
      Set<String> queryParamsForPartialMask, int queryLen) {
    StringBuilder maskedUrl = new StringBuilder(queryLen);
    for (NameValuePair keyValuePair : keyValueList) {
      String key = keyValuePair.getName();
      if (key.isEmpty()) {
        throw new IllegalArgumentException("Query param key should not be empty");
      }
      String value = keyValuePair.getValue();
      maskedUrl.append(key);
      maskedUrl.append(EQUAL);
      if (value != null && !value.isEmpty()) { //no mask
        if (queryParamsForFullMask.contains(key)) {
          maskedUrl.append(FULL_MASK);
        } else if (queryParamsForPartialMask.contains(key)) {
          int valueLen = value.length();
          int maskedLen = valueLen > PARTIAL_MASK_VISIBLE_LEN
              ? PARTIAL_MASK_VISIBLE_LEN : valueLen / 2;
          maskedUrl.append(value, 0, valueLen - maskedLen);
          maskedUrl.append(StringUtils.repeat(CHAR_MASK, maskedLen));
        } else {
          maskedUrl.append(value);
        }
      }
      maskedUrl.append(AND_MARK);
    }
    maskedUrl.deleteCharAt(maskedUrl.length() - 1);
    return maskedUrl.toString();
  }

  public static String encodedUrlStr(String url) {
    try {
      return URLEncoder.encode(url, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return "https%3A%2F%2Ffailed%2Fto%2Fencode%2Furl";
    }
  }

  public static String getMaskedUrl(URL url) {
    String queryString = url.getQuery();
    if (queryString == null) {
      return url.toString();
    }
    List<NameValuePair> queryKeyValueList = URLEncodedUtils
        .parse(queryString, StandardCharsets.UTF_8);
    String maskedQueryString = maskUrlQueryParameters(queryKeyValueList,
        FULL_MASK_PARAM_KEYS, PARTIAL_MASK_PARAM_KEYS, queryString.length());
    return url.toString().replace(queryString, maskedQueryString);
  }

  private UriUtils() {
  }
}
