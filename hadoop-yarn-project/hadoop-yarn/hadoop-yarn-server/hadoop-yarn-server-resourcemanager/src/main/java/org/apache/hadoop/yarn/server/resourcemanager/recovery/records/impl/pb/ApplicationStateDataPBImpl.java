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

package org.apache.hadoop.yarn.server.resourcemanager.recovery.records.impl.pb;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.ipc.CallerContext;
import org.apache.hadoop.ipc.protobuf.RpcHeaderProtos;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ApplicationTimeoutType;
import org.apache.hadoop.yarn.api.records.impl.pb.ApplicationSubmissionContextPBImpl;
import org.apache.hadoop.yarn.api.records.impl.pb.ProtoUtils;
import org.apache.hadoop.yarn.proto.YarnProtos.ApplicationTimeoutMapProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.ApplicationStateDataProto;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.ApplicationStateDataProtoOrBuilder;
import org.apache.hadoop.yarn.proto.YarnServerResourceManagerRecoveryProtos.RMAppStateProto;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.records.ApplicationStateData;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppState;

import org.apache.hadoop.thirdparty.protobuf.ByteString;
import org.apache.hadoop.thirdparty.protobuf.TextFormat;

public class ApplicationStateDataPBImpl extends ApplicationStateData {
  ApplicationStateDataProto proto = 
            ApplicationStateDataProto.getDefaultInstance();
  ApplicationStateDataProto.Builder builder = null;
  boolean viaProto = false;
  
  private ApplicationSubmissionContext applicationSubmissionContext = null;
  private Map<ApplicationTimeoutType, Long> applicationTimeouts = null;
  
  public ApplicationStateDataPBImpl() {
    builder = ApplicationStateDataProto.newBuilder();
  }

  public ApplicationStateDataPBImpl(
      ApplicationStateDataProto proto) {
    this.proto = proto;
    viaProto = true;
  }

  @Override
  public ApplicationStateDataProto getProto() {
    mergeLocalToProto();
    proto = viaProto ? proto : builder.build();
    viaProto = true;
    return proto;
  }

  private void mergeLocalToBuilder() {
    if (this.applicationSubmissionContext != null) {
      builder.setApplicationSubmissionContext(
          ((ApplicationSubmissionContextPBImpl)applicationSubmissionContext)
          .getProto());
    }

    if (this.applicationTimeouts != null) {
      addApplicationTimeouts();
    }
  }

  private void mergeLocalToProto() {
    if (viaProto) 
      maybeInitBuilder();
    mergeLocalToBuilder();
    proto = builder.build();
    viaProto = true;
  }

  private void maybeInitBuilder() {
    if (viaProto || builder == null) {
      builder = ApplicationStateDataProto.newBuilder(proto);
    }
    viaProto = false;
  }

  @Override
  public long getSubmitTime() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    if (!p.hasSubmitTime()) {
      return -1;
    }
    return (p.getSubmitTime());
  }

  @Override
  public void setSubmitTime(long submitTime) {
    maybeInitBuilder();
    builder.setSubmitTime(submitTime);
  }

  @Override
  public long getStartTime() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    return p.getStartTime();
  }

  @Override
  public void setStartTime(long startTime) {
    maybeInitBuilder();
    builder.setStartTime(startTime);
  }


  @Override
  public long getLaunchTime() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    return p.getLaunchTime();
  }

  @Override
  public void setLaunchTime(long launchTime) {
    maybeInitBuilder();
    builder.setLaunchTime(launchTime);
  }

  @Override
  public String getUser() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    if (!p.hasUser()) {
      return null;
    }
    return (p.getUser());

  }
  
  @Override
  public void setUser(String user) {
    maybeInitBuilder();
    builder.setUser(user);
  }

  @Override
  public String getRealUser() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    if (!p.hasRealUser()) {
      return null;
    }
    return (p.getRealUser());
  }

  @Override
  public void setRealUser(String realUser) {
    maybeInitBuilder();
    builder.setRealUser(realUser);
  }

  @Override
  public ApplicationSubmissionContext getApplicationSubmissionContext() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    if(applicationSubmissionContext != null) {
      return applicationSubmissionContext;
    }
    if (!p.hasApplicationSubmissionContext()) {
      return null;
    }
    applicationSubmissionContext = 
        new ApplicationSubmissionContextPBImpl(
            p.getApplicationSubmissionContext());
    return applicationSubmissionContext;
  }

  @Override
  public void setApplicationSubmissionContext(
      ApplicationSubmissionContext context) {
    maybeInitBuilder();
    if (context == null) {
      builder.clearApplicationSubmissionContext();
    }
    this.applicationSubmissionContext = context;
  }

  @Override
  public RMAppState getState() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    if (!p.hasApplicationState()) {
      return null;
    }
    return convertFromProtoFormat(p.getApplicationState());
  }

  @Override
  public void setState(RMAppState finalState) {
    maybeInitBuilder();
    if (finalState == null) {
      builder.clearApplicationState();
      return;
    }
    builder.setApplicationState(convertToProtoFormat(finalState));
  }

  @Override
  public String getDiagnostics() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    if (!p.hasDiagnostics()) {
      return null;
    }
    return p.getDiagnostics();
  }

  @Override
  public void setDiagnostics(String diagnostics) {
    maybeInitBuilder();
    if (diagnostics == null) {
      builder.clearDiagnostics();
      return;
    }
    builder.setDiagnostics(diagnostics);
  }

  @Override
  public long getFinishTime() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    return p.getFinishTime();
  }

  @Override
  public void setFinishTime(long finishTime) {
    maybeInitBuilder();
    builder.setFinishTime(finishTime);
  }

  @Override
  public int hashCode() {
    return getProto().hashCode();
  }

  @Override
  public boolean equals(Object other) {
    if (other == null)
      return false;
    if (other.getClass().isAssignableFrom(this.getClass())) {
      return this.getProto().equals(this.getClass().cast(other).getProto());
    }
    return false;
  }
  
  @Override
  public CallerContext getCallerContext() {
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    RpcHeaderProtos.RPCCallerContextProto pbContext = p.getCallerContext();
    if (pbContext != null) {
      CallerContext context = new CallerContext.Builder(pbContext.getContext())
          .setSignature(pbContext.getSignature().toByteArray()).build();
      return context;
    }

    return null;
  }

  @Override
  public void setCallerContext(CallerContext callerContext) {
    if (callerContext != null) {
      maybeInitBuilder();

      RpcHeaderProtos.RPCCallerContextProto.Builder b = RpcHeaderProtos.RPCCallerContextProto
          .newBuilder();
      if (callerContext.getContext() != null) {
        b.setContext(callerContext.getContext());
      }
      if (callerContext.getSignature() != null) {
        b.setSignature(ByteString.copyFrom(callerContext.getSignature()));
      }

      builder.setCallerContext(b);
    }
  }

  @Override
  public String toString() {
    return TextFormat.shortDebugString(getProto());
  }

  private static String RM_APP_PREFIX = "RMAPP_";
  public static RMAppStateProto convertToProtoFormat(RMAppState e) {
    return RMAppStateProto.valueOf(RM_APP_PREFIX + e.name());
  }
  public static RMAppState convertFromProtoFormat(RMAppStateProto e) {
    return RMAppState.valueOf(e.name().replace(RM_APP_PREFIX, ""));
  }

  @Override
  public Map<ApplicationTimeoutType, Long> getApplicationTimeouts() {
    initApplicationTimeout();
    return this.applicationTimeouts;
  }

  private void initApplicationTimeout() {
    if (this.applicationTimeouts != null) {
      return;
    }
    ApplicationStateDataProtoOrBuilder p = viaProto ? proto : builder;
    List<ApplicationTimeoutMapProto> lists = p.getApplicationTimeoutsList();
    this.applicationTimeouts =
        new HashMap<ApplicationTimeoutType, Long>(lists.size());
    for (ApplicationTimeoutMapProto timeoutProto : lists) {
      this.applicationTimeouts.put(
          ProtoUtils
              .convertFromProtoFormat(timeoutProto.getApplicationTimeoutType()),
          timeoutProto.getTimeout());
    }
  }

  @Override
  public void setApplicationTimeouts(
      Map<ApplicationTimeoutType, Long> appTimeouts) {
    if (appTimeouts == null) {
      return;
    }
    initApplicationTimeout();
    this.applicationTimeouts.clear();
    this.applicationTimeouts.putAll(appTimeouts);
  }

  private void addApplicationTimeouts() {
    maybeInitBuilder();
    builder.clearApplicationTimeouts();
    if (applicationTimeouts == null) {
      return;
    }
    Iterable<? extends ApplicationTimeoutMapProto> values =
        new Iterable<ApplicationTimeoutMapProto>() {

          @Override
          public Iterator<ApplicationTimeoutMapProto> iterator() {
            return new Iterator<ApplicationTimeoutMapProto>() {
              private Iterator<ApplicationTimeoutType> iterator =
                  applicationTimeouts.keySet().iterator();

              @Override
              public boolean hasNext() {
                return iterator.hasNext();
              }

              @Override
              public ApplicationTimeoutMapProto next() {
                ApplicationTimeoutType key = iterator.next();
                return ApplicationTimeoutMapProto.newBuilder()
                    .setTimeout(applicationTimeouts.get(key))
                    .setApplicationTimeoutType(
                        ProtoUtils.convertToProtoFormat(key))
                    .build();
              }

              @Override
              public void remove() {
                throw new UnsupportedOperationException();
              }
            };
          }
        };
    this.builder.addAllApplicationTimeouts(values);
  }
}
