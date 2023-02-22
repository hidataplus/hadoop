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
package org.apache.hadoop.mapred;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.OutputStream;

import static org.junit.Assert.assertEquals;

public class TestIFileStreams {
  @Test
  public void testIFileStream() throws Exception {
    final int DLEN = 100;
    DataOutputBuffer dob = new DataOutputBuffer(DLEN + 4);
    IFileOutputStream ifos = new IFileOutputStream(dob);
    for (int i = 0; i < DLEN; ++i) {
      ifos.write(i);
    }
    ifos.close();
    DataInputBuffer dib = new DataInputBuffer();
    dib.reset(dob.getData(), DLEN + 4);
    IFileInputStream ifis = new IFileInputStream(dib, 104, new Configuration());
    for (int i = 0; i < DLEN; ++i) {
      assertEquals(i, ifis.read());
    }
    ifis.close();
  }
  @Test
  public void testBadIFileStream() throws Exception {
    final int DLEN = 100;
    DataOutputBuffer dob = new DataOutputBuffer(DLEN + 4);
    IFileOutputStream ifos = new IFileOutputStream(dob);
    for (int i = 0; i < DLEN; ++i) {
      ifos.write(i);
    }
    ifos.close();
    DataInputBuffer dib = new DataInputBuffer();
    final byte[] b = dob.getData();
    ++b[17];
    dib.reset(b, DLEN + 4);
    IFileInputStream ifis = new IFileInputStream(dib, 104, new Configuration());
    int i = 0;
    try {
      while (i < DLEN) {
        if (17 == i) {
          assertEquals(18, ifis.read());
        } else {
          assertEquals(i, ifis.read());
        }
        ++i;
      }
      ifis.close();
    } catch (ChecksumException e) {
      assertEquals("Unexpected bad checksum", DLEN - 1, i);
      return;
    }
    fail("Did not detect bad data in checksum");
  }
  @Test
  public void testBadLength() throws Exception {
    final int DLEN = 100;
    DataOutputBuffer dob = new DataOutputBuffer(DLEN + 4);
    IFileOutputStream ifos = new IFileOutputStream(dob);
    for (int i = 0; i < DLEN; ++i) {
      ifos.write(i);
    }
    ifos.close();
    DataInputBuffer dib = new DataInputBuffer();
    dib.reset(dob.getData(), DLEN + 4);
    IFileInputStream ifis = new IFileInputStream(dib, 100, new Configuration());
    int i = 0;
    try {
      while (i < DLEN - 8) {
        assertEquals(i++, ifis.read());
      }
      ifis.close();
    } catch (ChecksumException e) {
      assertEquals("Checksum before close", i, DLEN - 8);
      return;
    }
    fail("Did not detect bad data in checksum");
  }

  @Test
  public void testCloseStreamOnException() throws Exception {
    OutputStream outputStream = Mockito.mock(OutputStream.class);
    IFileOutputStream ifos = new IFileOutputStream(outputStream);
    Mockito.doThrow(new IOException("Dummy Exception")).when(outputStream)
        .flush();
    try {
      ifos.close();
      fail("IOException is not thrown");
    } catch (IOException ioe) {
      assertEquals("Dummy Exception", ioe.getMessage());
    }
    Mockito.verify(outputStream).close();
  }
}
