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

package org.apache.hadoop.yarn.api.protocolrecords.impl.pb;

import org.apache.hadoop.yarn.api.protocolrecords.ReservationDeleteRequest;
import org.apache.hadoop.yarn.api.records.ReservationId;
import org.apache.hadoop.yarn.api.records.impl.pb.ReservationIdPBImpl;
import org.apache.hadoop.yarn.proto.YarnProtos.ReservationIdProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.ReservationDeleteRequestProto;
import org.apache.hadoop.yarn.proto.YarnServiceProtos.ReservationDeleteRequestProtoOrBuilder;

import org.apache.hadoop.thirdparty.protobuf.TextFormat;

public class ReservationDeleteRequestPBImpl extends ReservationDeleteRequest {

  ReservationDeleteRequestProto proto = ReservationDeleteRequestProto
      .getDefaultInstance();
  ReservationDeleteRequestProto.Builder builder = null;
  boolean viaProto = false;

  private ReservationId reservationId;

  public ReservationDeleteRequestPBImpl() {
    builder = ReservationDeleteRequestProto.newBuilder();
  }

  public ReservationDeleteRequestPBImpl(ReservationDeleteRequestProto proto) {
    this.proto = proto;
    viaProto = true;
  }

  public ReservationDeleteRequestProto getProto() {
    mergeLocalToProto();
    proto = viaProto ? proto : builder.build();
    viaProto = true;
    return proto;
  }

  private void mergeLocalToBuilder() {
    if (this.reservationId != null) {
      builder.setReservationId(convertToProtoFormat(this.reservationId));
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
      builder = ReservationDeleteRequestProto.newBuilder(proto);
    }
    viaProto = false;
  }

  @Override
  public ReservationId getReservationId() {
    ReservationDeleteRequestProtoOrBuilder p = viaProto ? proto : builder;
    if (reservationId != null) {
      return reservationId;
    }
    if (!p.hasReservationId()) {
      return null;
    }
    reservationId = convertFromProtoFormat(p.getReservationId());
    return reservationId;
  }

  @Override
  public void setReservationId(ReservationId reservationId) {
    maybeInitBuilder();
    if (reservationId == null) {
      builder.clearReservationId();
      return;
    }
    this.reservationId = reservationId;
  }

  private ReservationIdPBImpl convertFromProtoFormat(ReservationIdProto p) {
    return new ReservationIdPBImpl(p);
  }

  private ReservationIdProto convertToProtoFormat(ReservationId t) {
    return ((ReservationIdPBImpl) t).getProto();
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
  public String toString() {
    return TextFormat.shortDebugString(getProto());
  }

}
