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

package org.apache.hadoop.mapreduce.v2.app.launcher;


import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.yarn.event.EventHandler;

public interface ContainerLauncher 
    extends EventHandler<ContainerLauncherEvent> {

  enum EventType {
    CONTAINER_REMOTE_LAUNCH,
    CONTAINER_REMOTE_CLEANUP,
    // When TaskAttempt receives TA_CONTAINER_COMPLETED,
    // it will notify ContainerLauncher so that the container can be removed
    // from ContainerLauncher's launched containers list
    // Otherwise, ContainerLauncher will try to stop the containers as part of
    // serviceStop.
    CONTAINER_COMPLETED
  }

}
