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
package org.apache.hadoop.security;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.io.retry.Idempotent;
import org.apache.hadoop.security.KerberosInfo;

/**
 * Protocol use 
 *
 */
@KerberosInfo(
    serverPrincipal=CommonConfigurationKeys.HADOOP_SECURITY_SERVICE_USER_NAME_KEY)
@InterfaceAudience.LimitedPrivate({"HDFS", "MapReduce"})
@InterfaceStability.Evolving
public interface RefreshUserMappingsProtocol {
  
  /**
   * Version 1: Initial version.
   */
  public static final long versionID = 1L;

  /**
   * Refresh user to group mappings.
   * @throws IOException
   */
  @Idempotent
  public void refreshUserToGroupsMappings() throws IOException;
  
  /**
   * Refresh superuser proxy group list
   * @throws IOException
   */
  @Idempotent
  public void refreshSuperUserGroupsConfiguration() throws IOException;
}
