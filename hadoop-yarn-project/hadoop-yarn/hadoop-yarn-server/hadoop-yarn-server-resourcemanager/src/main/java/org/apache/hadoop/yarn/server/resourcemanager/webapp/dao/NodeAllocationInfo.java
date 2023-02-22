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

package org.apache.hadoop.yarn.server.resourcemanager.webapp.dao;

import org.apache.hadoop.yarn.server.resourcemanager.webapp.RMWSConsts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.activities.NodeAllocation;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlRootElement;

/*
 * DAO object to display each node allocation in node heartbeat.
 */
@XmlRootElement
@XmlAccessorType(XmlAccessType.FIELD)
public class NodeAllocationInfo {
  private String partition;
  private String updatedContainerId;
  private String finalAllocationState;
  private ActivityNodeInfo root = null;

  private static final Logger LOG =
      LoggerFactory.getLogger(NodeAllocationInfo.class);

  NodeAllocationInfo() {
  }

  NodeAllocationInfo(NodeAllocation allocation,
      RMWSConsts.ActivitiesGroupBy groupBy) {
    this.partition = allocation.getPartition();
    this.updatedContainerId = allocation.getContainerId();
    this.finalAllocationState = allocation.getFinalAllocationState().name();
    root = new ActivityNodeInfo(allocation.getRoot(), groupBy);
  }

  public String getPartition() {
    return partition;
  }

  public String getUpdatedContainerId() {
    return updatedContainerId;
  }

  public String getFinalAllocationState() {
    return finalAllocationState;
  }

  public ActivityNodeInfo getRoot() {
    return root;
  }
}
