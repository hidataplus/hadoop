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

package org.apache.hadoop.mapreduce.protocol;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.mapreduce.security.token.delegation.DelegationTokenSelector;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.ipc.VersionedProtocol;
import org.apache.hadoop.mapreduce.Cluster.JobTrackerStatus;
import org.apache.hadoop.mapreduce.ClusterMetrics;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.JobStatus;
import org.apache.hadoop.mapreduce.QueueAclsInfo;
import org.apache.hadoop.mapreduce.QueueInfo;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskCompletionEvent;
import org.apache.hadoop.mapreduce.TaskReport;
import org.apache.hadoop.mapreduce.TaskTrackerInfo;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.security.token.delegation.DelegationTokenIdentifier;
import org.apache.hadoop.mapreduce.server.jobtracker.JTConfig;
import org.apache.hadoop.mapreduce.v2.LogParams;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.KerberosInfo;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenInfo;

/** 
 * Protocol that a JobClient and the central JobTracker use to communicate.  The
 * JobClient can use these methods to submit a Job for execution, and learn about
 * the current system status.
 */ 
@KerberosInfo(
    serverPrincipal = JTConfig.JT_USER_NAME)
@TokenInfo(DelegationTokenSelector.class)
@InterfaceAudience.Private
@InterfaceStability.Stable
public interface ClientProtocol extends VersionedProtocol {
  /* 
   *Changing the versionID to 2L since the getTaskCompletionEvents method has
   *changed.
   *Changed to 4 since killTask(String,boolean) is added
   *Version 4: added jobtracker state to ClusterStatus
   *Version 5: max_tasks in ClusterStatus is replaced by
   * max_map_tasks and max_reduce_tasks for HADOOP-1274
   * Version 6: change the counters representation for HADOOP-2248
   * Version 7: added getAllJobs for HADOOP-2487
   * Version 8: change {job|task}id's to use corresponding objects rather that strings.
   * Version 9: change the counter representation for HADOOP-1915
   * Version 10: added getSystemDir for HADOOP-3135
   * Version 11: changed JobProfile to include the queue name for HADOOP-3698
   * Version 12: Added getCleanupTaskReports and 
   *             cleanupProgress to JobStatus as part of HADOOP-3150
   * Version 13: Added getJobQueueInfos and getJobQueueInfo(queue name)
   *             and getAllJobs(queue) as a part of HADOOP-3930
   * Version 14: Added setPriority for HADOOP-4124
   * Version 15: Added KILLED status to JobStatus as part of HADOOP-3924            
   * Version 16: Added getSetupTaskReports and 
   *             setupProgress to JobStatus as part of HADOOP-4261           
   * Version 17: getClusterStatus returns the amount of memory used by 
   *             the server. HADOOP-4435
   * Version 18: Added blacklisted trackers to the ClusterStatus 
   *             for HADOOP-4305
   * Version 19: Modified TaskReport to have TIP status and modified the
   *             method getClusterStatus() to take a boolean argument
   *             for HADOOP-4807
   * Version 20: Modified ClusterStatus to have the tasktracker expiry
   *             interval for HADOOP-4939
   * Version 21: Modified TaskID to be aware of the new TaskTypes                                 
   * Version 22: Added method getQueueAclsForCurrentUser to get queue acls info
   *             for a user
   * Version 23: Modified the JobQueueInfo class to inlucde queue state.
   *             Part of HADOOP-5913.  
   * Version 24: Modified ClusterStatus to include BlackListInfo class which 
   *             encapsulates reasons and report for blacklisted node.          
   * Version 25: Added fields to JobStatus for HADOOP-817.   
   * Version 26: Added properties to JobQueueInfo as part of MAPREDUCE-861.
   *              added new api's getRootQueues and
   *              getChildQueues(String queueName)
   * Version 27: Changed protocol to use new api objects. And the protocol is 
   *             renamed from JobSubmissionProtocol to ClientProtocol.
   * Version 28: Added getJobHistoryDir() as part of MAPREDUCE-975.
   * Version 29: Added reservedSlots, runningTasks and totalJobSubmissions
   *             to ClusterMetrics as part of MAPREDUCE-1048.
   * Version 30: Job submission files are uploaded to a staging area under
   *             user home dir. JobTracker reads the required files from the
   *             staging area using user credentials passed via the rpc.
   * Version 31: Added TokenStorage to submitJob      
   * Version 32: Added delegation tokens (add, renew, cancel)
   * Version 33: Added JobACLs to JobStatus as part of MAPREDUCE-1307
   * Version 34: Modified submitJob to use Credentials instead of TokenStorage.
   * Version 35: Added the method getQueueAdmins(queueName) as part of
   *             MAPREDUCE-1664.
   * Version 36: Added the method getJobTrackerStatus() as part of
   *             MAPREDUCE-2337.
   * Version 37: More efficient serialization format for framework counters
   *             (MAPREDUCE-901)
   * Version 38: Added getLogFilePath(JobID, TaskAttemptID) as part of 
   *             MAPREDUCE-3146
   */
  public static final long versionID = 37L;

  /**
   * Allocate a name for the job.
   * @return a unique job name for submitting jobs.
   * @throws IOException
   */
  public JobID getNewJobID() throws IOException, InterruptedException;

  /**
   * Submit a Job for execution.  Returns the latest profile for
   * that job.
   */
  public JobStatus submitJob(JobID jobId, String jobSubmitDir, Credentials ts)
      throws IOException, InterruptedException;

  /**
   * Get the current status of the cluster
   * 
   * @return summary of the state of the cluster
   */
  public ClusterMetrics getClusterMetrics() 
  throws IOException, InterruptedException;

  /**
   * Get the JobTracker's status.
   * 
   * @return {@link JobTrackerStatus} of the JobTracker
   * @throws IOException
   * @throws InterruptedException
   */
  public JobTrackerStatus getJobTrackerStatus() throws IOException,
    InterruptedException;

  public long getTaskTrackerExpiryInterval() throws IOException,
                                               InterruptedException;
  
  /**
   * Get the administrators of the given job-queue.
   * This method is for hadoop internal use only.
   * @param queueName
   * @return Queue administrators ACL for the queue to which job is
   *         submitted to
   * @throws IOException
   */
  public AccessControlList getQueueAdmins(String queueName) throws IOException;

  /**
   * Kill the indicated job
   */
  public void killJob(JobID jobid) throws IOException, InterruptedException;

  /**
   * Set the priority of the specified job
   * @param jobid ID of the job
   * @param priority Priority to be set for the job
   */
  public void setJobPriority(JobID jobid, String priority) 
  throws IOException, InterruptedException;
  
  /**
   * Kill indicated task attempt.
   * @param taskId the id of the task to kill.
   * @param shouldFail if true the task is failed and added to failed tasks list, otherwise
   * it is just killed, w/o affecting job failure status.  
   */ 
  public boolean killTask(TaskAttemptID taskId, boolean shouldFail) 
  throws IOException, InterruptedException;
  
  /**
   * Grab a handle to a job that is already known to the JobTracker.
   * @return Status of the job, or null if not found.
   */
  public JobStatus getJobStatus(JobID jobid) 
  throws IOException, InterruptedException;

  /**
   * Grab the current job counters
   */
  public Counters getJobCounters(JobID jobid) 
  throws IOException, InterruptedException;
    
  /**
   * Grab a bunch of info on the tasks that make up the job
   */
  public TaskReport[] getTaskReports(JobID jobid, TaskType type)
  throws IOException, InterruptedException;

  /**
   * A MapReduce system always operates on a single filesystem.  This 
   * function returns the fs name.  ('local' if the localfs; 'addr:port' 
   * if dfs).  The client can then copy files into the right locations 
   * prior to submitting the job.
   */
  public String getFilesystemName() throws IOException, InterruptedException;

  /** 
   * Get all the jobs submitted. 
   * @return array of JobStatus for the submitted jobs
   */
  public JobStatus[] getAllJobs() throws IOException, InterruptedException;
  
  /**
   * Get task completion events for the jobid, starting from fromEventId. 
   * Returns empty array if no events are available. 
   * @param jobid job id 
   * @param fromEventId event id to start from.
   * @param maxEvents the max number of events we want to look at 
   * @return array of task completion events. 
   * @throws IOException
   */
  public TaskCompletionEvent[] getTaskCompletionEvents(JobID jobid,
    int fromEventId, int maxEvents) throws IOException, InterruptedException;
    
  /**
   * Get the diagnostics for a given task in a given job
   * @param taskId the id of the task
   * @return an array of the diagnostic messages
   */
  public String[] getTaskDiagnostics(TaskAttemptID taskId) 
  throws IOException, InterruptedException;

  /** 
   * Get all active trackers in cluster. 
   * @return array of TaskTrackerInfo
   */
  public TaskTrackerInfo[] getActiveTrackers() 
  throws IOException, InterruptedException;

  /** 
   * Get all blacklisted trackers in cluster. 
   * @return array of TaskTrackerInfo
   */
  public TaskTrackerInfo[] getBlacklistedTrackers() 
  throws IOException, InterruptedException;

  /**
   * Grab the jobtracker system directory path 
   * where job-specific files are to be placed.
   * 
   * @return the system directory where job-specific files are to be placed.
   */
  public String getSystemDir() throws IOException, InterruptedException;
  
  /**
   * Get a hint from the JobTracker 
   * where job-specific files are to be placed.
   * 
   * @return the directory where job-specific files are to be placed.
   */
  public String getStagingAreaDir() throws IOException, InterruptedException;

  /**
   * Gets the directory location of the completed job history files.
   * @throws IOException
   * @throws InterruptedException
   */
  public String getJobHistoryDir() 
  throws IOException, InterruptedException;

  /**
   * Gets set of Queues associated with the Job Tracker
   * 
   * @return Array of the Queue Information Object
   * @throws IOException 
   */
  public QueueInfo[] getQueues() throws IOException, InterruptedException;
  
  /**
   * Gets scheduling information associated with the particular Job queue
   * 
   * @param queueName Queue Name
   * @return Scheduling Information of the Queue
   * @throws IOException 
   */
  public QueueInfo getQueue(String queueName) 
  throws IOException, InterruptedException;
  
  /**
   * Gets the Queue ACLs for current user
   * @return array of QueueAclsInfo object for current user.
   * @throws IOException
   */
  public QueueAclsInfo[] getQueueAclsForCurrentUser() 
  throws IOException, InterruptedException;
  
  /**
   * Gets the root level queues.
   * @return array of JobQueueInfo object.
   * @throws IOException
   */
  public QueueInfo[] getRootQueues() throws IOException, InterruptedException;
  
  /**
   * Returns immediate children of queueName.
   * @param queueName
   * @return array of JobQueueInfo which are children of queueName
   * @throws IOException
   */
  public QueueInfo[] getChildQueues(String queueName) 
  throws IOException, InterruptedException;

  /**
   * Get a new delegation token.
   * @param renewer the user other than the creator (if any) that can renew the 
   *        token
   * @return the new delegation token
   * @throws IOException
   * @throws InterruptedException
   */
  public 
  Token<DelegationTokenIdentifier> getDelegationToken(Text renewer
                                                      ) throws IOException,
                                                          InterruptedException;
  
  /**
   * Renew an existing delegation token
   * @param token the token to renew
   * @return the new expiration time
   * @throws IOException
   * @throws InterruptedException
   */
  public long renewDelegationToken(Token<DelegationTokenIdentifier> token
                                   ) throws IOException,
                                            InterruptedException;
  
  /**
   * Cancel a delegation token.
   * @param token the token to cancel
   * @throws IOException
   * @throws InterruptedException
   */
  public void cancelDelegationToken(Token<DelegationTokenIdentifier> token
                                    ) throws IOException,
                                             InterruptedException;
  
  /**
   * Gets the location of the log file for a job if no taskAttemptId is
   * specified, otherwise gets the log location for the taskAttemptId.
   * @param jobID the jobId.
   * @param taskAttemptID the taskAttemptId.
   * @return log params.
   * @throws IOException
   * @throws InterruptedException
   */
  public LogParams getLogFileParams(JobID jobID, TaskAttemptID taskAttemptID)
      throws IOException, InterruptedException;
}
