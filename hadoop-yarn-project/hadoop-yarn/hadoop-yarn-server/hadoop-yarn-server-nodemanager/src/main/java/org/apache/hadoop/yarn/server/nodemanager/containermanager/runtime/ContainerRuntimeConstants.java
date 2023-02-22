/*
 * *
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 */

package org.apache.hadoop.yarn.server.nodemanager.containermanager.runtime;

import org.apache.hadoop.classification.InterfaceAudience.Private;

public class ContainerRuntimeConstants {

  /* Switch container runtimes. Work in progress: These
   * parameters may be changed/removed in the future. */

  @Private
  public static final String ENV_CONTAINER_TYPE =
      "YARN_CONTAINER_RUNTIME_TYPE";

  @Private
  public static final String CONTAINER_RUNTIME_DOCKER =
      "docker";

  @Private
  public static final String CONTAINER_RUNTIME_RUNC =
      "runc";
}
