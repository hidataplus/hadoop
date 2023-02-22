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

package org.apache.hadoop.fs.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIOException;
import org.apache.hadoop.ipc.RemoteException;
import org.junit.Test;

public class TestPathExceptions {

  protected String path = "some/file";
  protected String error = "KABOOM";

  @Test
  public void testWithDefaultString() throws Exception {
    PathIOException pe = new PathIOException(path);
    assertEquals(new Path(path), pe.getPath());
    assertEquals("`" + path + "': Input/output error", pe.getMessage());
  }

  @Test
  public void testWithThrowable() throws Exception {
    IOException ioe = new IOException("KABOOM");    
    PathIOException pe = new PathIOException(path, ioe);
    assertEquals(new Path(path), pe.getPath());
    assertEquals("`" + path + "': Input/output error: " + error, pe.getMessage());
  }

  @Test
  public void testWithCustomString() throws Exception {
    PathIOException pe = new PathIOException(path, error);
    assertEquals(new Path(path), pe.getPath());
    assertEquals("`" + path + "': " + error, pe.getMessage());
  }

  @Test
  public void testRemoteExceptionUnwrap() throws Exception {
    PathIOException pe;
    RemoteException re;
    IOException ie;
    
    pe = new PathIOException(path);
    re = new RemoteException(PathIOException.class.getName(), "test constructor1");
    ie = re.unwrapRemoteException();
    assertTrue(ie instanceof PathIOException);
    ie = re.unwrapRemoteException(PathIOException.class);
    assertTrue(ie instanceof PathIOException);

    pe = new PathIOException(path, "constructor2");
    re = new RemoteException(PathIOException.class.getName(), "test constructor2");
    ie = re.unwrapRemoteException();
    assertTrue(ie instanceof PathIOException);
    ie = re.unwrapRemoteException(PathIOException.class);
    assertTrue(ie instanceof PathIOException);    
  }
}
