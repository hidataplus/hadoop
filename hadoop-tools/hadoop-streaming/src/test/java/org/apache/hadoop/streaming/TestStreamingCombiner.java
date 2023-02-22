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

package org.apache.hadoop.streaming;

import java.io.IOException;

import org.apache.hadoop.mapred.Counters;

import org.junit.Test;
import static org.junit.Assert.*;

public class TestStreamingCombiner extends TestStreaming {

  protected String combine = UtilTest.makeJavaCommand(
      UniqApp.class, new String[]{""});
  
  public TestStreamingCombiner() throws IOException {
    super();
  }
  
  protected String[] genArgs() {
    args.add("-combiner");
    args.add(combine);
    return super.genArgs();
  }

  @Test
  public void testCommandLine() throws Exception {
    super.testCommandLine();
    // validate combiner counters
    String counterGrp = "org.apache.hadoop.mapred.Task$Counter";
    Counters counters = job.running_.getCounters();
    assertTrue(counters.findCounter(
               counterGrp, "COMBINE_INPUT_RECORDS").getValue() != 0);
    assertTrue(counters.findCounter(
               counterGrp, "COMBINE_OUTPUT_RECORDS").getValue() != 0);
  }
}
