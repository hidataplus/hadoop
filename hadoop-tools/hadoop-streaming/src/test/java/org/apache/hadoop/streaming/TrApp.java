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

import java.io.*;

import org.apache.hadoop.streaming.Environment;

/** A minimal Java implementation of /usr/bin/tr.
 *  Used to test the usage of external applications without adding
 *  platform-specific dependencies.
 *  Use TrApp as mapper only. For reducer, use TrAppReduce.
 */
public class TrApp
{

  public TrApp(char find, char replace)
  {
    this.find = find;
    this.replace = replace;
  }

  void testParentJobConfToEnvVars() throws IOException
  {
    env = new Environment();
    // test that some JobConf properties are exposed as expected     
    // Note the dots translated to underscore: 
    // property names have been escaped in PipeMapRed.safeEnvVarName()
    expectDefined("mapreduce_cluster_local_dir");
    expect("mapreduce_map_output_key_class", "org.apache.hadoop.io.Text");
    expect("mapreduce_map_output_value_class", "org.apache.hadoop.io.Text");

    expect("mapreduce_task_ismap", "true");
    expectDefined("mapreduce_task_attempt_id");

    expectDefined("mapreduce_map_input_file");
    expectDefined("mapreduce_map_input_length");

    expectDefined("mapreduce_task_io_sort_factor");

    // the FileSplit context properties are not available in local hadoop..
    // so can't check them in this test.

    // verify some deprecated properties appear for older stream jobs
    expect("map_input_file", env.getProperty("mapreduce_map_input_file"));
    expect("map_input_length", env.getProperty("mapreduce_map_input_length"));
  }

  // this runs in a subprocess; won't use JUnit's assertTrue()    
  void expect(String evName, String evVal) throws IOException
  {
    String got = env.getProperty(evName);
    if (!evVal.equals(got)) {
      String msg = "FAIL evName=" + evName + " got=" + got + " expect=" + evVal;
      throw new IOException(msg);
    }
  }

  void expectDefined(String evName) throws IOException
  {
    String got = env.getProperty(evName);
    if (got == null) {
      String msg = "FAIL evName=" + evName + " is undefined. Expect defined.";
      throw new IOException(msg);
    }
  }

  public void go() throws IOException
  {
    testParentJobConfToEnvVars();
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
    String line;

    while ((line = in.readLine()) != null) {
      String out = line.replace(find, replace);
      System.out.println(out);
      System.err.println("reporter:counter:UserCounters,InputLines,1");
    }
  }

  public static void main(String[] args) throws IOException
  {
    args[0] = CUnescape(args[0]);
    args[1] = CUnescape(args[1]);
    TrApp app = new TrApp(args[0].charAt(0), args[1].charAt(0));
    app.go();
  }

  public static String CUnescape(String s)
  {
    if (s.equals("\\n")) {
      return "\n";
    } else {
      return s;
    }
  }
  char find;
  char replace;
  Environment env;
}
