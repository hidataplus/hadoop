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

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.file.tfile.TFile.Reader;
import org.apache.hadoop.io.file.tfile.TFile.Writer;
import org.apache.hadoop.io.file.tfile.TFile.Reader.Scanner;
import org.apache.hadoop.test.GenericTestUtils;

/**
 * 
 * Streaming interfaces test case class using GZ compression codec, base class
 * of none and LZO compression classes.
 * 
 */

public class TestTFileStreams {
  private static String ROOT = GenericTestUtils.getTestDir().getAbsolutePath();

  private final static int BLOCK_SIZE = 512;
  private final static int K = 1024;
  private final static int M = K * K;
  protected boolean skip = false;
  private FileSystem fs;
  private Configuration conf;
  private Path path;
  private FSDataOutputStream out;
  Writer writer;

  private String compression = Compression.Algorithm.GZ.getName();
  private String comparator = "memcmp";
  private final String outputFile = getClass().getSimpleName();

  public void init(String compression, String comparator) {
    this.compression = compression;
    this.comparator = comparator;
  }

  @Before
  public void setUp() throws IOException {
    conf = new Configuration();
    path = new Path(ROOT, outputFile);
    fs = path.getFileSystem(conf);
    out = fs.create(path);
    writer = new Writer(out, BLOCK_SIZE, compression, comparator, conf);
  }

  @After
  public void tearDown() throws IOException {
    if (!skip) {
      try {
        closeOutput();
      } catch (Exception e) {
        // no-op
      }
      fs.delete(path, true);
    }
  }

  @Test
  public void testNoEntry() throws IOException {
    if (skip)
      return;
    closeOutput();
    TestTFileByteArrays.readRecords(fs, path, 0, conf);
  }

  @Test
  public void testOneEntryKnownLength() throws IOException {
    if (skip)
      return;
    writeRecords(1, true, true);

    TestTFileByteArrays.readRecords(fs, path, 1, conf);
  }

  @Test
  public void testOneEntryUnknownLength() throws IOException {
    if (skip)
      return;
    writeRecords(1, false, false);

    // TODO: will throw exception at getValueLength, it's inconsistent though;
    // getKeyLength returns a value correctly, though initial length is -1
    TestTFileByteArrays.readRecords(fs, path, 1, conf);
  }

  // known key length, unknown value length
  @Test
  public void testOneEntryMixedLengths1() throws IOException {
    if (skip)
      return;
    writeRecords(1, true, false);

    TestTFileByteArrays.readRecords(fs, path, 1, conf);
  }

  // unknown key length, known value length
  @Test
  public void testOneEntryMixedLengths2() throws IOException {
    if (skip)
      return;
    writeRecords(1, false, true);

    TestTFileByteArrays.readRecords(fs, path, 1, conf);
  }

  @Test
  public void testTwoEntriesKnownLength() throws IOException {
    if (skip)
      return;
    writeRecords(2, true, true);

    TestTFileByteArrays.readRecords(fs, path, 2, conf);
  }

  // Negative test
  @Test
  public void testFailureAddKeyWithoutValue() throws IOException {
    if (skip)
      return;
    DataOutputStream dos = writer.prepareAppendKey(-1);
    dos.write("key0".getBytes());
    try {
      closeOutput();
      fail("Cannot add only a key without a value. ");
    }
    catch (IllegalStateException e) {
      // noop, expecting an exception
    }
  }

  @Test
  public void testFailureAddValueWithoutKey() throws IOException {
    if (skip)
      return;
    DataOutputStream outValue = null;
    try {
      outValue = writer.prepareAppendValue(6);
      outValue.write("value0".getBytes());
      fail("Cannot add a value without adding key first. ");
    }
    catch (Exception e) {
      // noop, expecting an exception
    }
    finally {
      if (outValue != null) {
        outValue.close();
      }
    }
  }

  @Test
  public void testFailureOneEntryKnownLength() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(2);
    try {
      outKey.write("key0".getBytes());
      fail("Specified key length mismatched the actual key length.");
    }
    catch (IOException e) {
      // noop, expecting an exception
    }

    DataOutputStream outValue = null;
    try {
      outValue = writer.prepareAppendValue(6);
      outValue.write("value0".getBytes());
    }
    catch (Exception e) {
      // noop, expecting an exception
    }
  }

  @Test
  public void testFailureKeyTooLong() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(2);
    try {
      outKey.write("key0".getBytes());
      outKey.close();
      fail("Key is longer than requested.");
    }
    catch (Exception e) {
      // noop, expecting an exception
    }
    finally {
    }
  }

  @Test
  public void testFailureKeyTooShort() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(4);
    outKey.write("key0".getBytes());
    outKey.close();
    DataOutputStream outValue = writer.prepareAppendValue(15);
    try {
      outValue.write("value0".getBytes());
      outValue.close();
      fail("Value is shorter than expected.");
    }
    catch (Exception e) {
      // noop, expecting an exception
    }
    finally {
    }
  }

  @Test
  public void testFailureValueTooLong() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(4);
    outKey.write("key0".getBytes());
    outKey.close();
    DataOutputStream outValue = writer.prepareAppendValue(3);
    try {
      outValue.write("value0".getBytes());
      outValue.close();
      fail("Value is longer than expected.");
    }
    catch (Exception e) {
      // noop, expecting an exception
    }

    try {
      outKey.close();
      outKey.close();
    }
    catch (Exception e) {
      fail("Second or more close() should have no effect.");
    }
  }

  @Test
  public void testFailureValueTooShort() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(8);
    try {
      outKey.write("key0".getBytes());
      outKey.close();
      fail("Key is shorter than expected.");
    }
    catch (Exception e) {
      // noop, expecting an exception
    }
    finally {
    }
  }

  @Test
  public void testFailureCloseKeyStreamManyTimesInWriter() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(4);
    try {
      outKey.write("key0".getBytes());
      outKey.close();
    }
    catch (Exception e) {
      // noop, expecting an exception
    }
    finally {
      try {
        outKey.close();
      }
      catch (Exception e) {
        // no-op
      }
    }
    outKey.close();
    outKey.close();
    assertTrue("Multiple close should have no effect.", true);
  }

  @Test
  public void testFailureKeyLongerThan64K() throws IOException {
    if (skip)
      return;
    try {
      DataOutputStream outKey = writer.prepareAppendKey(64 * K + 1);
      fail("Failed to handle key longer than 64K.");
    }
    catch (IndexOutOfBoundsException e) {
      // noop, expecting exceptions
    }
    closeOutput();
  }

  @Test
  public void testFailureKeyLongerThan64K_2() throws IOException {
    if (skip)
      return;
    DataOutputStream outKey = writer.prepareAppendKey(-1);
    try {
      byte[] buf = new byte[K];
      Random rand = new Random();
      for (int nx = 0; nx < K + 2; nx++) {
        rand.nextBytes(buf);
        outKey.write(buf);
      }
      outKey.close();
      fail("Failed to handle key longer than 64K.");
    }
    catch (EOFException e) {
      // noop, expecting exceptions
    }
    finally {
      try {
        closeOutput();
      }
      catch (Exception e) {
        // no-op
      }
    }
  }

  @Test
  public void testFailureNegativeOffset() throws IOException {
    if (skip)
      return;
    writeRecords(2, true, true);

    Reader reader = new Reader(fs.open(path), fs.getFileStatus(path).getLen(), conf);
    Scanner scanner = reader.createScanner();
    byte[] buf = new byte[K];
    try {
      scanner.entry().getKey(buf, -1);
      fail("Failed to handle key negative offset.");
    }
    catch (Exception e) {
      // noop, expecting exceptions
    }
    finally {
    }
    scanner.close();
    reader.close();
  }

  /**
   * Verify that the compressed data size is less than raw data size.
   * 
   * @throws IOException
   */
  @Test
  public void testFailureCompressionNotWorking() throws IOException {
    if (skip)
      return;
    long rawDataSize = writeRecords(10000, false, false, false);
    if (!compression.equalsIgnoreCase(Compression.Algorithm.NONE.getName())) {
      assertTrue(out.getPos() < rawDataSize);
    }
    closeOutput();
  }

  @Test
  public void testFailureCompressionNotWorking2() throws IOException {
    if (skip)
      return;
    long rawDataSize = writeRecords(10000, true, true, false);
    if (!compression.equalsIgnoreCase(Compression.Algorithm.NONE.getName())) {
      assertTrue(out.getPos() < rawDataSize);
    }
    closeOutput();
  }

  private long writeRecords(int count, boolean knownKeyLength,
      boolean knownValueLength, boolean close) throws IOException {
    long rawDataSize = 0;
    for (int nx = 0; nx < count; nx++) {
      String key = TestTFileByteArrays.composeSortedKey("key", nx);
      DataOutputStream outKey =
          writer.prepareAppendKey(knownKeyLength ? key.length() : -1);
      outKey.write(key.getBytes());
      outKey.close();
      String value = "value" + nx;
      DataOutputStream outValue =
          writer.prepareAppendValue(knownValueLength ? value.length() : -1);
      outValue.write(value.getBytes());
      outValue.close();
      rawDataSize +=
          WritableUtils.getVIntSize(key.getBytes().length)
              + key.getBytes().length
              + WritableUtils.getVIntSize(value.getBytes().length)
              + value.getBytes().length;
    }
    if (close) {
      closeOutput();
    }
    return rawDataSize;
  }

  private long writeRecords(int count, boolean knownKeyLength,
      boolean knownValueLength) throws IOException {
    return writeRecords(count, knownKeyLength, knownValueLength, true);
  }

  private void closeOutput() throws IOException {
    if (writer != null) {
      writer.close();
      writer = null;
    }
    if (out != null) {
      out.close();
      out = null;
    }
  }
}
