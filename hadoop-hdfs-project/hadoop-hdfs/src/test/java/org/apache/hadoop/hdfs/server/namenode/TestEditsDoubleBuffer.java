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
package org.apache.hadoop.hdfs.server.namenode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Assert;
import org.junit.Test;

public class TestEditsDoubleBuffer {
  @Test
  public void testDoubleBuffer() throws IOException {
    EditsDoubleBuffer buf = new EditsDoubleBuffer(1024);
    
    assertTrue(buf.isFlushed());
    byte[] data = new byte[100];
    buf.writeRaw(data, 0, data.length);
    assertEquals("Should count new data correctly",
        data.length, buf.countBufferedBytes());

    assertTrue("Writing to current buffer should not affect flush state",
        buf.isFlushed());

    // Swap the buffers
    buf.setReadyToFlush();
    assertEquals("Swapping buffers should still count buffered bytes",
        data.length, buf.countBufferedBytes());
    assertFalse(buf.isFlushed());
 
    // Flush to a stream
    DataOutputBuffer outBuf = new DataOutputBuffer();
    buf.flushTo(outBuf);
    assertEquals(data.length, outBuf.getLength());
    assertTrue(buf.isFlushed());
    assertEquals(0, buf.countBufferedBytes());
    
    // Write some more
    buf.writeRaw(data, 0, data.length);
    assertEquals("Should count new data correctly",
        data.length, buf.countBufferedBytes());
    buf.setReadyToFlush();
    buf.flushTo(outBuf);
    
    assertEquals(data.length * 2, outBuf.getLength());
    
    assertEquals(0, buf.countBufferedBytes());

    outBuf.close();
  }
  
  @Test
  public void shouldFailToCloseWhenUnflushed() throws IOException {
    EditsDoubleBuffer buf = new EditsDoubleBuffer(1024);
    buf.writeRaw(new byte[1], 0, 1);
    try {
      buf.close();
      fail("Did not fail to close with unflushed data");
    } catch (IOException ioe) {
      if (!ioe.toString().contains("still to be flushed")) {
        throw ioe;
      }
    }
  }

  @Test
  public void testDumpEdits() throws IOException {
    final int defaultBufferSize = 256;
    final int fakeLogVersion =
        NameNodeLayoutVersion.Feature.ROLLING_UPGRADE
            .getInfo().getLayoutVersion();
    EditsDoubleBuffer buffer = new EditsDoubleBuffer(defaultBufferSize);
    FSEditLogOp.OpInstanceCache cache = new FSEditLogOp.OpInstanceCache();

    String src = "/testdumpedits";
    short replication = 1;

    FSEditLogOp.SetReplicationOp op =
        FSEditLogOp.SetReplicationOp.getInstance(cache.get())
        .setPath(src)
        .setReplication(replication);
    op.setTransactionId(1);
    buffer.writeOp(op, fakeLogVersion);

    src = "/testdumpedits2";

    FSEditLogOp.DeleteOp op2 =
        FSEditLogOp.DeleteOp.getInstance(cache.get())
            .setPath(src)
            .setTimestamp(0);
    op2.setTransactionId(2);
    buffer.writeOp(op2, fakeLogVersion);

    FSEditLogOp.AllocateBlockIdOp op3 =
        FSEditLogOp.AllocateBlockIdOp.getInstance(cache.get())
            .setBlockId(0);
    op3.setTransactionId(3);
    buffer.writeOp(op3, fakeLogVersion);

    GenericTestUtils.LogCapturer logs =
        GenericTestUtils.LogCapturer.captureLogs(EditsDoubleBuffer.LOG);
    try {
      buffer.close();
      fail();
    } catch (IOException ioe) {
      GenericTestUtils.assertExceptionContains(
          "bytes still to be flushed and cannot be closed.",
          ioe);
      EditsDoubleBuffer.LOG.info("Exception expected: ", ioe);
    }
    logs.stopCapturing();
    // Make sure ops are dumped into log in human readable format.
    Assert.assertTrue("expected " + op.toString() + " in the log",
        logs.getOutput().contains(op.toString()));
    Assert.assertTrue("expected " + op2.toString() + " in the log",
        logs.getOutput().contains(op2.toString()));
    Assert.assertTrue("expected " + op3.toString() + " in the log",
        logs.getOutput().contains(op3.toString()));
  }
}
