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

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestTypedBytesStreaming {

  protected File INPUT_FILE = new File("target/input.txt");
  protected File OUTPUT_DIR = new File("target/out");
  protected String input = "roses.are.red\nviolets.are.blue\nbunnies.are.pink\n";
  protected String map = UtilTest.makeJavaCommand(TypedBytesMapApp.class, new String[]{"."});
  protected String reduce = UtilTest.makeJavaCommand(TypedBytesReduceApp.class, new String[0]);
  protected String outputExpect = "are\t3\nred\t1\nblue\t1\npink\t1\nroses\t1\nbunnies\t1\nviolets\t1\n";
  
  public TestTypedBytesStreaming() throws IOException {
    UtilTest utilTest = new UtilTest(getClass().getName());
    utilTest.checkUserDir();
    utilTest.redirectIfAntJunit();
  }

  protected void createInput() throws IOException {
    DataOutputStream out = new DataOutputStream(new FileOutputStream(INPUT_FILE.getAbsoluteFile()));
    out.write(input.getBytes("UTF-8"));
    out.close();
  }

  protected String[] genArgs() {
    return new String[] {
      "-input", INPUT_FILE.getAbsolutePath(),
      "-output", OUTPUT_DIR.getAbsolutePath(),
      "-mapper", map,
      "-reducer", reduce,
      "-jobconf", "mapreduce.task.files.preserve.failedtasks=true",
      "-jobconf", "stream.tmpdir="+System.getProperty("test.build.data","/tmp"),
      "-io", "typedbytes"
    };
  }

  @Before
  @After
  public void cleanupOutput() throws Exception {
    FileUtil.fullyDelete(OUTPUT_DIR.getAbsoluteFile());
    INPUT_FILE.delete();
    createInput();
  }

  @Test
  public void testCommandLine() throws Exception {
    // During tests, the default Configuration will use a local mapred
    // So don't specify -config or -cluster
    StreamJob job = new StreamJob();
    job.setConf(new Configuration());
    job.run(genArgs());
    File outFile = new File(OUTPUT_DIR, "part-00000").getAbsoluteFile();
    String output = StreamUtil.slurp(outFile);
    outFile.delete();
    System.out.println("   map=" + map);
    System.out.println("reduce=" + reduce);
    System.err.println("outEx1=" + outputExpect);
    System.err.println("  out1=" + output);
    assertEquals(outputExpect, output);
  }
}
