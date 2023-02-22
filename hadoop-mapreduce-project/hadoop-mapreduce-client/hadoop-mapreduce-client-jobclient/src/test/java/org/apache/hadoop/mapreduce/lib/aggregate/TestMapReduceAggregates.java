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
package org.apache.hadoop.mapreduce.lib.aggregate;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.MapReduceTestUtil;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.junit.Test;

import java.text.NumberFormat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestMapReduceAggregates {

  private static NumberFormat idFormat = NumberFormat.getInstance();
    static {
      idFormat.setMinimumIntegerDigits(4);
      idFormat.setGroupingUsed(false);
  }

  @Test
  public void testAggregates() throws Exception {
    launch();
  }

  public static void launch() throws Exception {
    Configuration conf = new Configuration();
    FileSystem fs = FileSystem.get(conf);
    int numOfInputLines = 20;

    String baseDir = System.getProperty("test.build.data", "build/test/data");
    Path OUTPUT_DIR = new Path(baseDir + "/output_for_aggregates_test");
    Path INPUT_DIR = new Path(baseDir + "/input_for_aggregates_test");
    String inputFile = "input.txt";
    fs.delete(INPUT_DIR, true);
    fs.mkdirs(INPUT_DIR);
    fs.delete(OUTPUT_DIR, true);

    StringBuffer inputData = new StringBuffer();
    StringBuffer expectedOutput = new StringBuffer();
    expectedOutput.append("max\t19\n");
    expectedOutput.append("min\t1\n"); 

    FSDataOutputStream fileOut = fs.create(new Path(INPUT_DIR, inputFile));
    for (int i = 1; i < numOfInputLines; i++) {
      expectedOutput.append("count_").append(idFormat.format(i));
      expectedOutput.append("\t").append(i).append("\n");

      inputData.append(idFormat.format(i));
      for (int j = 1; j < i; j++) {
        inputData.append(" ").append(idFormat.format(i));
      }
      inputData.append("\n");
    }
    expectedOutput.append("value_as_string_max\t9\n");
    expectedOutput.append("value_as_string_min\t1\n");
    expectedOutput.append("uniq_count\t15\n");


    fileOut.write(inputData.toString().getBytes("utf-8"));
    fileOut.close();

    System.out.println("inputData:");
    System.out.println(inputData.toString());

    conf.setInt(ValueAggregatorJobBase.DESCRIPTOR_NUM, 1);
    conf.set(ValueAggregatorJobBase.DESCRIPTOR + ".0", 
      "UserDefined,org.apache.hadoop.mapreduce.lib.aggregate.AggregatorTests");
    conf.setLong(UniqValueCount.MAX_NUM_UNIQUE_VALUES, 14);
    
    Job job = Job.getInstance(conf);
    FileInputFormat.setInputPaths(job, INPUT_DIR);
    job.setInputFormatClass(TextInputFormat.class);
    FileOutputFormat.setOutputPath(job, OUTPUT_DIR);
    job.setOutputFormatClass(TextOutputFormat.class);
    job.setMapOutputKeyClass(Text.class);
    job.setMapOutputValueClass(Text.class);
    job.setOutputKeyClass(Text.class);
    job.setOutputValueClass(Text.class);
    job.setNumReduceTasks(1);
    job.setMapperClass(ValueAggregatorMapper.class);
    job.setReducerClass(ValueAggregatorReducer.class);
    job.setCombinerClass(ValueAggregatorCombiner.class);


    job.waitForCompletion(true);

    assertTrue(job.isSuccessful());
    //
    // Finally, we compare the reconstructed answer key with the
    // original one.  Remember, we need to ignore zero-count items
    // in the original key.
    //
    String outdata = MapReduceTestUtil.readOutput(OUTPUT_DIR, conf);
    System.out.println("full out data:");
    System.out.println(outdata.toString());
    outdata = outdata.substring(0, expectedOutput.toString().length());

    assertEquals(expectedOutput.toString(),outdata);
    fs.delete(OUTPUT_DIR, true);
    fs.delete(INPUT_DIR, true);
  }
}
