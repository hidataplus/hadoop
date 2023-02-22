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

package org.apache.hadoop.mapreduce.v2.app.job;

import java.util.List;

import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.v2.api.records.Phase;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptReport;
import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptState;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.NodeId;


/**
 * Read only view of TaskAttempt.
 */
public interface TaskAttempt {
  TaskAttemptId getID();
  TaskAttemptReport getReport();
  List<String> getDiagnostics();
  Counters getCounters();
  float getProgress();
  Phase getPhase();
  TaskAttemptState getState();

  /** 
   * Has attempt reached the final state or not.
   * @return true if it has finished, else false
   */
  boolean isFinished();

  /**
   * @return the container ID if a container is assigned, otherwise null.
   */
  ContainerId getAssignedContainerID();

  /**
   * @return container mgr address if a container is assigned, otherwise null.
   */
  String getAssignedContainerMgrAddress();
  
  /**
   * @return node's id if a container is assigned, otherwise null.
   */
  NodeId getNodeId();
  
  /**
   * @return node's http address if a container is assigned, otherwise null.
   */
  String getNodeHttpAddress();
  
  /**
   * @return node's rack name if a container is assigned, otherwise null.
   */
  String getNodeRackName();

  /** 
   * @return time at which container is launched. If container is not launched
   * yet, returns 0.
   */
  long getLaunchTime();

  /** 
   * @return attempt's finish time. If attempt is not finished
   *  yet, returns 0.
   */
  long getFinishTime();
  
  /**
   * @return The attempt's shuffle finish time if the attempt is a reduce. If
   * attempt is not finished yet, returns 0.
   */
  long getShuffleFinishTime();

  /**
   * @return The attempt's sort or merge finish time if the attempt is a reduce. 
   * If attempt is not finished yet, returns 0.
   */
  long getSortFinishTime();

  /**
   * @return the port shuffle is on.
   */
  public int getShufflePort();
}
