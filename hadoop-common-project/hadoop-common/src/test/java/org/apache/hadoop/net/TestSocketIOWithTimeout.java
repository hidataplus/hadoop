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
package org.apache.hadoop.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.Pipe;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.MultithreadedTestUtil;
import org.apache.hadoop.test.MultithreadedTestUtil.TestContext;
import org.apache.hadoop.test.MultithreadedTestUtil.TestingThread;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.Shell;
import org.apache.hadoop.io.nativeio.NativeIO;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.*;

/**
 * This tests timout out from SocketInputStream and
 * SocketOutputStream using pipes.
 * 
 * Normal read and write using these streams are tested by pretty much
 * every DFS unit test.
 */
public class TestSocketIOWithTimeout {

  static final Logger LOG =
      LoggerFactory.getLogger(TestSocketIOWithTimeout.class);
  
  private static int TIMEOUT = 1*1000; 
  private static String TEST_STRING = "1234567890";

  private MultithreadedTestUtil.TestContext ctx = new TestContext();
  
  private static final int PAGE_SIZE = (int) NativeIO.POSIX.getCacheManipulator().getOperatingSystemPageSize();

  private void doIO(InputStream in, OutputStream out,
      int expectedTimeout) throws IOException {
    /* Keep on writing or reading until we get SocketTimeoutException.
     * It expects this exception to occur within 100 millis of TIMEOUT.
     */
    byte buf[] = new byte[PAGE_SIZE + 19];
    
    while (true) {
      long start = Time.now();
      try {
        if (in != null) {
          in.read(buf);
        } else {
          out.write(buf);
        }
      } catch (SocketTimeoutException e) {
        long diff = Time.now() - start;
        LOG.info("Got SocketTimeoutException as expected after " + 
                 diff + " millis : " + e.getMessage());
        assertTrue(Math.abs(expectedTimeout - diff) <=
          TestNetUtils.TIME_FUDGE_MILLIS);
        break;
      }
    }
  }
  
  @Test
  public void testSocketIOWithTimeout() throws Exception {
    
    // first open pipe:
    Pipe pipe = Pipe.open();
    Pipe.SourceChannel source = pipe.source();
    Pipe.SinkChannel sink = pipe.sink();
    
    try {
      final InputStream in = new SocketInputStream(source, TIMEOUT);
      OutputStream out = new SocketOutputStream(sink, TIMEOUT);
      
      byte[] writeBytes = TEST_STRING.getBytes();
      byte[] readBytes = new byte[writeBytes.length];
      byte byteWithHighBit = (byte)0x80;
      
      out.write(writeBytes);
      out.write(byteWithHighBit);
      doIO(null, out, TIMEOUT);
      
      in.read(readBytes);
      assertTrue(Arrays.equals(writeBytes, readBytes));
      assertEquals(byteWithHighBit & 0xff, in.read());
      doIO(in, null, TIMEOUT);
      
      // Change timeout on the read side.
      ((SocketInputStream)in).setTimeout(TIMEOUT * 2);
      doIO(in, null, TIMEOUT * 2);
      
      
      /*
       * Verify that it handles interrupted threads properly.
       * Use a large timeout and expect the thread to return quickly
       * upon interruption.
       */
      ((SocketInputStream)in).setTimeout(0);
      TestingThread thread = new TestingThread(ctx) {
        @Override
        public void doWork() throws Exception {
          try {
            in.read();
            fail("Did not fail with interrupt");
          } catch (InterruptedIOException ste) {
            LOG.info("Got expection while reading as expected : " + 
                ste.getMessage());
          }
        }
      };
      ctx.addThread(thread);
      ctx.startThreads();
      // If the thread is interrupted before it calls read()
      // then it throws ClosedByInterruptException due to
      // some Java quirk. Waiting for it to call read()
      // gets it into select(), so we get the expected
      // InterruptedIOException.
      Thread.sleep(1000);
      thread.interrupt();
      ctx.stop();

      //make sure the channels are still open
      assertTrue(source.isOpen());
      assertTrue(sink.isOpen());
      
      // Nevertheless, the output stream is closed, because
      // a partial write may have succeeded (see comment in
      // SocketOutputStream#write(byte[]), int, int)
      // This portion of the test cannot pass on Windows due to differences in
      // behavior of partial writes.  Windows appears to buffer large amounts of
      // written data and send it all atomically, thus making it impossible to
      // simulate a partial write scenario.  Attempts were made to switch the
      // test from using a pipe to a network socket and also to use larger and
      // larger buffers in doIO.  Nothing helped the situation though.
      if (!Shell.WINDOWS) {
        try {
          out.write(1);
          fail("Did not throw");
        } catch (IOException ioe) {
          GenericTestUtils.assertExceptionContains(
              "stream is closed", ioe);
        }
      }
      
      out.close();
      assertFalse(sink.isOpen());
      
      // close sink and expect -1 from source.read()
      assertEquals(-1, in.read());
      
      // make sure close() closes the underlying channel.
      in.close();
      assertFalse(source.isOpen());
      
    } finally {
      if (source != null) {
        source.close();
      }
      if (sink != null) {
        sink.close();
      }
    }
  }

  @Test
  public void testSocketIOWithTimeoutByMultiThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Runnable ioTask = () -> {
      try {
        Pipe pipe = Pipe.open();
        try (Pipe.SourceChannel source = pipe.source();
             InputStream in = new SocketInputStream(source, TIMEOUT);
             Pipe.SinkChannel sink = pipe.sink();
             OutputStream out = new SocketOutputStream(sink, TIMEOUT)) {

          byte[] writeBytes = TEST_STRING.getBytes();
          byte[] readBytes = new byte[writeBytes.length];
          latch.await();

          out.write(writeBytes);
          doIO(null, out, TIMEOUT);

          in.read(readBytes);
          assertArrayEquals(writeBytes, readBytes);
          doIO(in, null, TIMEOUT);
        }
      } catch (Exception e) {
        fail(e.getMessage());
      }
    };

    int threadCnt = 64;
    ExecutorService threadPool = Executors.newFixedThreadPool(threadCnt);
    for (int i = 0; i < threadCnt; ++i) {
      threadPool.submit(ioTask);
    }

    Thread.sleep(1000);
    latch.countDown();

    threadPool.shutdown();
    assertTrue(threadPool.awaitTermination(3, TimeUnit.SECONDS));
  }

  @Test
  public void testSocketIOWithTimeoutInterrupted() throws Exception {
    Pipe pipe = Pipe.open();
    final int timeout = TIMEOUT * 10;

    try (Pipe.SourceChannel source = pipe.source();
        InputStream in = new SocketInputStream(source, timeout)) {

      TestingThread thread = new TestingThread(ctx) {
        @Override
        public void doWork() throws Exception {
          try {
            in.read();
            fail("Did not fail with interrupt");
          } catch (InterruptedIOException ste) {
            String detail = ste.getMessage();
            String totalString = "Total timeout mills is " + timeout;
            String leftString = "millis timeout left";

            assertTrue(detail.contains(totalString));
            assertTrue(detail.contains(leftString));
          }
        }
      };

      ctx.addThread(thread);
      ctx.startThreads();
      // If the thread is interrupted before it calls read()
      // then it throws ClosedByInterruptException due to
      // some Java quirk. Waiting for it to call read()
      // gets it into select(), so we get the expected
      // InterruptedIOException.
      Thread.sleep(1000);
      thread.interrupt();
      ctx.stop();
    }
  }

  @Test
  public void testSocketIOWithTimeoutInterruptedByMultiThread()
      throws Exception {
    final int timeout = TIMEOUT * 10;
    AtomicLong readCount = new AtomicLong();
    AtomicLong exceptionCount = new AtomicLong();
    Runnable ioTask = () -> {
      try {
        Pipe pipe = Pipe.open();
        try (Pipe.SourceChannel source = pipe.source();
             InputStream in = new SocketInputStream(source, timeout)) {
          in.read();
          readCount.incrementAndGet();
        } catch (InterruptedIOException ste) {
          exceptionCount.incrementAndGet();
        }
      } catch (Exception e) {
        fail(e.getMessage());
      }
    };

    int threadCnt = 64;
    ExecutorService threadPool = Executors.newFixedThreadPool(threadCnt);
    for (int i = 0; i < threadCnt; ++i) {
      threadPool.submit(ioTask);
    }
    Thread.sleep(1000);
    threadPool.shutdownNow();
    threadPool.awaitTermination(1, TimeUnit.SECONDS);

    assertEquals(0, readCount.get());
    assertEquals(threadCnt, exceptionCount.get());
  }
}
