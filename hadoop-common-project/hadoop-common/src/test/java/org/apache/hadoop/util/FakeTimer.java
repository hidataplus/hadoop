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

package org.apache.hadoop.util;

import java.util.concurrent.TimeUnit;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * FakeTimer can be used for test purposes to control the return values
 * from {{@link Timer}}.
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class FakeTimer extends Timer {
  private long now;
  private long nowNanos;

  /** Constructs a FakeTimer with a non-zero value */
  public FakeTimer() {
    // Initialize with a non-trivial value.
    now = 1577836800000L; // 2020-01-01 00:00:00,000+0000
    nowNanos = TimeUnit.MILLISECONDS.toNanos(1000);
  }

  @Override
  public long now() {
    return now;
  }

  @Override
  public long monotonicNow() {
    return TimeUnit.NANOSECONDS.toMillis(nowNanos);
  }

  @Override
  public long monotonicNowNanos() {
    return nowNanos;
  }

  /** Increases the time by milliseconds */
  public void advance(long advMillis) {
    now += advMillis;
    nowNanos += TimeUnit.MILLISECONDS.toNanos(advMillis);
  }

  /**
   * Increases the time by nanoseconds.
   * @param advNanos Nanoseconds to advance by.
   */
  public void advanceNanos(long advNanos) {
    now += TimeUnit.NANOSECONDS.toMillis(advNanos);
    nowNanos += advNanos;
  }
}
