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

package org.apache.hadoop.mapreduce.v2.api;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.hadoop.mapreduce.v2.api.protocolrecords.CancelDelegationTokenRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.CancelDelegationTokenResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.FailTaskAttemptRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.FailTaskAttemptResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetCountersRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetCountersResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetDelegationTokenRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetDelegationTokenResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetDiagnosticsRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetDiagnosticsResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetJobReportRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetJobReportResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskAttemptCompletionEventsRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskAttemptCompletionEventsResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskAttemptReportRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskAttemptReportResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskReportRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskReportResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskReportsRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.GetTaskReportsResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillJobRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillJobResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillTaskAttemptRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillTaskAttemptResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillTaskRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.KillTaskResponse;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.RenewDelegationTokenRequest;
import org.apache.hadoop.mapreduce.v2.api.protocolrecords.RenewDelegationTokenResponse;

public interface MRClientProtocol {
  /**
   * Address to which the client is connected
   * @return InetSocketAddress
   */
  public InetSocketAddress getConnectAddress();
  public GetJobReportResponse getJobReport(GetJobReportRequest request) throws IOException;
  public GetTaskReportResponse getTaskReport(GetTaskReportRequest request) throws IOException;
  public GetTaskAttemptReportResponse getTaskAttemptReport(GetTaskAttemptReportRequest request) throws IOException;
  public GetCountersResponse getCounters(GetCountersRequest request) throws IOException;
  public GetTaskAttemptCompletionEventsResponse getTaskAttemptCompletionEvents(GetTaskAttemptCompletionEventsRequest request) throws IOException;
  public GetTaskReportsResponse getTaskReports(GetTaskReportsRequest request) throws IOException;
  public GetDiagnosticsResponse getDiagnostics(GetDiagnosticsRequest request) throws IOException;
  public KillJobResponse killJob(KillJobRequest request) throws IOException;
  public KillTaskResponse killTask(KillTaskRequest request) throws IOException;
  public KillTaskAttemptResponse killTaskAttempt(KillTaskAttemptRequest request) throws IOException;
  public FailTaskAttemptResponse failTaskAttempt(FailTaskAttemptRequest request) throws IOException;
  public GetDelegationTokenResponse getDelegationToken(GetDelegationTokenRequest request) throws IOException;
  
  /**
   * Renew an existing delegation token.
   * 
   * @param request the delegation token to be renewed.
   * @return the new expiry time for the delegation token.
   * @throws IOException
   */
  public RenewDelegationTokenResponse renewDelegationToken(
      RenewDelegationTokenRequest request) throws IOException;

  /**
   * Cancel an existing delegation token.
   * 
   * @param request the delegation token to be cancelled.
   * @return an empty response.
   * @throws IOException
   */
  public CancelDelegationTokenResponse cancelDelegationToken(
      CancelDelegationTokenRequest request) throws IOException;
}
