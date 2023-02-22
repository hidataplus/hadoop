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
package org.apache.hadoop.hdfs.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;

import org.apache.hadoop.thirdparty.com.google.common.base.Joiner;
import org.apache.hadoop.thirdparty.com.google.common.base.Preconditions;
import org.apache.hadoop.thirdparty.com.google.common.collect.ImmutableSet;
import org.apache.hadoop.thirdparty.com.google.common.collect.Sets;

/**
 * LayoutFlags represent features which the FSImage and edit logs can either
 * support or not, independently of layout version.
 * 
 * Note: all flags starting with 'test' are reserved for unit test purposes.
 */
@InterfaceAudience.Private
public class LayoutFlags {

  /**
   * Read next int from given input stream. If the value is not 0 (unsupported
   * feature flags), throw appropriate IOException.
   *
   * @param in            The stream to read from.
   * @throws IOException  If next byte read from given stream is not 0.
   */
  public static void read(DataInputStream in) throws IOException {
    int length = in.readInt();
    if (length < 0) {
      throw new IOException("The length of the feature flag section " +
          "was negative at " + length + " bytes.");
    } else if (length > 0) {
      throw new IOException("Found feature flags which we can't handle. " +
          "Please upgrade your software.");
    }
  }

  private LayoutFlags() {
  }

  public static void write(DataOutputStream out) throws IOException {
    out.writeInt(0);
  }
}
