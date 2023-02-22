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

package org.apache.hadoop.yarn.api.protocolrecords;

import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.ReservationDefinition;
import org.apache.hadoop.yarn.api.records.ReservationId;
import org.apache.hadoop.yarn.util.Records;

/**
 * {@link ReservationDeleteRequest} captures the set of requirements the user
 * has to delete an existing reservation.
 * 
 * @see ReservationDefinition
 * 
 */
@Public
@Unstable
public abstract class ReservationDeleteRequest {

  @Public
  @Unstable
  public static ReservationDeleteRequest newInstance(ReservationId reservationId) {
    ReservationDeleteRequest request =
        Records.newRecord(ReservationDeleteRequest.class);
    request.setReservationId(reservationId);
    return request;
  }

  /**
   * Get the {@link ReservationId}, that corresponds to a valid resource
   * allocation in the scheduler (between start and end time of this
   * reservation)
   * 
   * @return the {@link ReservationId} representing the unique id of the
   *         corresponding reserved resource allocation in the scheduler
   */
  @Public
  @Unstable
  public abstract ReservationId getReservationId();

  /**
   * Set the {@link ReservationId}, that correspond to a valid resource
   * allocation in the scheduler (between start and end time of this
   * reservation)
   * 
   * @param reservationId the {@link ReservationId} representing the the unique
   *          id of the corresponding reserved resource allocation in the
   *          scheduler
   */
  @Public
  @Unstable
  public abstract void setReservationId(ReservationId reservationId);

}
