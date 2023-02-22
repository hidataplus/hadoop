/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.io.file.tfile;

import java.io.IOException;

import org.junit.After;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.file.tfile.TFile.Reader;
import org.apache.hadoop.io.file.tfile.TFile.Writer;
import org.apache.hadoop.io.file.tfile.TFile.Reader.Scanner;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class TestTFileUnsortedByteArrays {
  private static String ROOT = GenericTestUtils.getTestDir().getAbsolutePath();

  private final static int BLOCK_SIZE = 512;
  private final static int BUF_SIZE = 64;

  private FileSystem fs;
  private Configuration conf;
  private Path path;
  private FSDataOutputStream out;
  private Writer writer;

  private String compression = Compression.Algorithm.GZ.getName();
  private String outputFile = "TFileTestUnsorted";
  /*
   * pre-sampled numbers of records in one block, based on the given the
   * generated key and value strings
   */
  private int records1stBlock = 4314;
  private int records2ndBlock = 4108;

  public void init(String compression, String outputFile,
      int numRecords1stBlock, int numRecords2ndBlock) {
    this.compression = compression;
    this.outputFile = outputFile;
    this.records1stBlock = numRecords1stBlock;
    this.records2ndBlock = numRecords2ndBlock;
  }

  @Before
  public void setUp() throws IOException {
    conf = new Configuration();
    path = new Path(ROOT, outputFile);
    fs = path.getFileSystem(conf);
    out = fs.create(path);
    writer = new Writer(out, BLOCK_SIZE, compression, null, conf);
    writer.append("keyZ".getBytes(), "valueZ".getBytes());
    writer.append("keyM".getBytes(), "valueM".getBytes());
    writer.append("keyN".getBytes(), "valueN".getBytes());
    writer.append("keyA".getBytes(), "valueA".getBytes());
    closeOutput();
  }

  @After
  public void tearDown() throws IOException {
    fs.delete(path, true);
  }

  // we still can scan records in an unsorted TFile
  @Test
  public void testFailureScannerWithKeys() throws IOException {
    try (Reader reader =
        new Reader(fs.open(path), fs.getFileStatus(path).getLen(), conf)) {
      assertThat(reader.isSorted()).isFalse();
      assertThat(reader.getEntryCount()).isEqualTo(4);
      try {
        reader.createScannerByKey("aaa".getBytes(), "zzz".getBytes());
        fail("Failed to catch creating scanner with keys on unsorted file.");
      } catch (RuntimeException expected) {
      }
    }
  }

  // we still can scan records in an unsorted TFile
  @Test
  public void testScan() throws IOException {
    try (Reader reader =
        new Reader(fs.open(path), fs.getFileStatus(path).getLen(), conf)) {
      assertThat(reader.isSorted()).isFalse();
      assertThat(reader.getEntryCount()).isEqualTo(4);
      try (Scanner scanner = reader.createScanner()) {
        // read key and value
        byte[] kbuf = new byte[BUF_SIZE];
        int klen = scanner.entry().getKeyLength();
        scanner.entry().getKey(kbuf);
        assertThat(new String(kbuf, 0, klen)).isEqualTo("keyZ");

        byte[] vbuf = new byte[BUF_SIZE];
        int vlen = scanner.entry().getValueLength();
        scanner.entry().getValue(vbuf);
        assertThat(new String(vbuf, 0, vlen)).isEqualTo("valueZ");

        scanner.advance();

        // now try get value first
        vbuf = new byte[BUF_SIZE];
        vlen = scanner.entry().getValueLength();
        scanner.entry().getValue(vbuf);
        assertThat(new String(vbuf, 0, vlen)).isEqualTo("valueM");

        kbuf = new byte[BUF_SIZE];
        klen = scanner.entry().getKeyLength();
        scanner.entry().getKey(kbuf);
        assertThat(new String(kbuf, 0, klen)).isEqualTo("keyM");
      }
    }
  }

  // we still can scan records in an unsorted TFile
  @Test
  public void testScanRange() throws IOException {
    try (Reader reader =
        new Reader(fs.open(path), fs.getFileStatus(path).getLen(), conf)) {
      assertThat(reader.isSorted()).isFalse();
      assertThat(reader.getEntryCount()).isEqualTo(4);

      try (Scanner scanner = reader.createScanner()) {

        // read key and value
        byte[] kbuf = new byte[BUF_SIZE];
        int klen = scanner.entry().getKeyLength();
        scanner.entry().getKey(kbuf);
        assertThat(new String(kbuf, 0, klen)).isEqualTo("keyZ");

        byte[] vbuf = new byte[BUF_SIZE];
        int vlen = scanner.entry().getValueLength();
        scanner.entry().getValue(vbuf);
        assertThat(new String(vbuf, 0, vlen)).isEqualTo("valueZ");

        scanner.advance();

        // now try get value first
        vbuf = new byte[BUF_SIZE];
        vlen = scanner.entry().getValueLength();
        scanner.entry().getValue(vbuf);
        assertThat(new String(vbuf, 0, vlen)).isEqualTo("valueM");

        kbuf = new byte[BUF_SIZE];
        klen = scanner.entry().getKeyLength();
        scanner.entry().getKey(kbuf);
        assertThat(new String(kbuf, 0, klen)).isEqualTo("keyM");
      }
    }
  }

  @Test
  public void testFailureSeek() throws IOException {
    try (Reader reader = new Reader(fs.open(path),
        fs.getFileStatus(path).getLen(), conf);
        Scanner scanner = reader.createScanner()) {
      // can't find ceil
      try {
        scanner.lowerBound("keyN".getBytes());
        fail("Cannot search in a unsorted TFile!");
      }
      catch (Exception expected) {
      }

      // can't find higher
      try {
        scanner.upperBound("keyA".getBytes());
        fail("Cannot search higher in a unsorted TFile!");
      }
      catch (Exception expected) {
      }

      // can't seek
      try {
        scanner.seekTo("keyM".getBytes());
        fail("Cannot search a unsorted TFile!");
      }
      catch (Exception expected) {
      }
    }
  }

  private void closeOutput() throws IOException {
    if (writer != null) {
      writer.close();
      writer = null;
      out.close();
      out = null;
    }
  }
}
