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

package org.apache.hadoop.mapred;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;


/** 
 * <code>RunningJob</code> is the user-interface to query for details on a 
 * running Map-Reduce job.
 * 
 * <p>Clients can get hold of <code>RunningJob</code> via the {@link JobClient}
 * and then query the running-job for details such as name, configuration, 
 * progress etc.</p> 
 * 
 * @see JobClient
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public interface RunningJob {

  /**
   * Get the underlying job configuration
   *
   * @return the configuration of the job.
   */
  public Configuration getConfiguration();

  /**
   * Get the job identifier.
   * 
   * @return the job identifier.
   */
  public JobID getID();
  
  /** @deprecated This method is deprecated and will be removed. Applications should 
   * rather use {@link #getID()}.
   */
  @Deprecated
  public String getJobID();
  
  /**
   * Get the name of the job.
   * 
   * @return the name of the job.
   */
  public String getJobName();

  /**
   * Get the path of the submitted job configuration.
   * 
   * @return the path of the submitted job configuration.
   */
  public String getJobFile();

  /**
   * Get the URL where some job progress information will be displayed.
   * 
   * @return the URL where some job progress information will be displayed.
   */
  public String getTrackingURL();

  /**
   * Get the <i>progress</i> of the job's map-tasks, as a float between 0.0 
   * and 1.0.  When all map tasks have completed, the function returns 1.0.
   * 
   * @return the progress of the job's map-tasks.
   * @throws IOException
   */
  public float mapProgress() throws IOException;

  /**
   * Get the <i>progress</i> of the job's reduce-tasks, as a float between 0.0 
   * and 1.0.  When all reduce tasks have completed, the function returns 1.0.
   * 
   * @return the progress of the job's reduce-tasks.
   * @throws IOException
   */
  public float reduceProgress() throws IOException;

  /**
   * Get the <i>progress</i> of the job's cleanup-tasks, as a float between 0.0 
   * and 1.0.  When all cleanup tasks have completed, the function returns 1.0.
   * 
   * @return the progress of the job's cleanup-tasks.
   * @throws IOException
   */
  public float cleanupProgress() throws IOException;

  /**
   * Get the <i>progress</i> of the job's setup-tasks, as a float between 0.0 
   * and 1.0.  When all setup tasks have completed, the function returns 1.0.
   * 
   * @return the progress of the job's setup-tasks.
   * @throws IOException
   */
  public float setupProgress() throws IOException;

  /**
   * Check if the job is finished or not. 
   * This is a non-blocking call.
   * 
   * @return <code>true</code> if the job is complete, else <code>false</code>.
   * @throws IOException
   */
  public boolean isComplete() throws IOException;

  /**
   * Check if the job completed successfully. 
   * 
   * @return <code>true</code> if the job succeeded, else <code>false</code>.
   * @throws IOException
   */
  public boolean isSuccessful() throws IOException;
  
  /**
   * Blocks until the job is complete.
   * 
   * @throws IOException
   */
  public void waitForCompletion() throws IOException;

  /**
   * Returns the current state of the Job.
   * {@link JobStatus}
   * 
   * @throws IOException
   */
  public int getJobState() throws IOException;
  
  /**
   * Returns a snapshot of the current status, {@link JobStatus}, of the Job.
   * Need to call again for latest information.
   * 
   * @throws IOException
   */
  public JobStatus getJobStatus() throws IOException;

  /**
   * Kill the running job. Blocks until all job tasks have been killed as well.
   * If the job is no longer running, it simply returns.
   * 
   * @throws IOException
   */
  public void killJob() throws IOException;
  
  /**
   * Set the priority of a running job.
   * @param priority the new priority for the job.
   * @throws IOException
   */
  public void setJobPriority(String priority) throws IOException;
  
  /**
   * Get events indicating completion (success/failure) of component tasks.
   *  
   * @param startFrom index to start fetching events from
   * @return an array of {@link TaskCompletionEvent}s
   * @throws IOException
   */
  public TaskCompletionEvent[] getTaskCompletionEvents(int startFrom) 
  throws IOException;
  
  /**
   * Kill indicated task attempt.
   * 
   * @param taskId the id of the task to be terminated.
   * @param shouldFail if true the task is failed and added to failed tasks 
   *                   list, otherwise it is just killed, w/o affecting 
   *                   job failure status.  
   * @throws IOException
   */
  public void killTask(TaskAttemptID taskId, boolean shouldFail) throws IOException;
  
  /** @deprecated Applications should rather use {@link #killTask(TaskAttemptID, boolean)}*/
  @Deprecated
  public void killTask(String taskId, boolean shouldFail) throws IOException;
  
  /**
   * Gets the counters for this job.
   * 
   * @return the counters for this job or null if the job has been retired.
   * @throws IOException
   */
  public Counters getCounters() throws IOException;
  
  /**
   * Gets the diagnostic messages for a given task attempt.
   * @param taskid
   * @return the list of diagnostic messages for the task
   * @throws IOException
   */
  public String[] getTaskDiagnostics(TaskAttemptID taskid) throws IOException;

  /**
   * Get the url where history file is archived. Returns empty string if 
   * history file is not available yet. 
   * 
   * @return the url where history file is archived
   * @throws IOException
   */
  public String getHistoryUrl() throws IOException;

  /**
   * Check whether the job has been removed from JobTracker memory and retired.
   * On retire, the job history file is copied to a location known by 
   * {@link #getHistoryUrl()}
   * @return <code>true</code> if the job retired, else <code>false</code>.
   * @throws IOException
   */
  public boolean isRetired() throws IOException;
  
  /**
   * Get failure info for the job.
   * @return the failure info for the job.
   * @throws IOException
   */
  public String getFailureInfo() throws IOException;
}
