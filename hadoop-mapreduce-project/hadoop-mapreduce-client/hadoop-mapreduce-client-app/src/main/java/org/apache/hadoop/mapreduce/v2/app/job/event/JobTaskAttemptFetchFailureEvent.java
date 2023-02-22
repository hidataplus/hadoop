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

package org.apache.hadoop.mapreduce.v2.app.job.event;

import java.util.List;

import org.apache.hadoop.mapreduce.v2.api.records.TaskAttemptId;



public class JobTaskAttemptFetchFailureEvent extends JobEvent {

  private final TaskAttemptId reduce;
  private final List<TaskAttemptId> maps;
  private final String hostname;

  public JobTaskAttemptFetchFailureEvent(TaskAttemptId reduce, 
      List<TaskAttemptId> maps, String host) {
    super(reduce.getTaskId().getJobId(),
        JobEventType.JOB_TASK_ATTEMPT_FETCH_FAILURE);
    this.reduce = reduce;
    this.maps = maps;
    this.hostname = host;
  }

  public List<TaskAttemptId> getMaps() {
    return maps;
  }

  public TaskAttemptId getReduce() {
    return reduce;
  }

  public String getHost() {
    return hostname;
  }
}
