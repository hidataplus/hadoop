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

import java.io.IOException;
import java.util.List;
import java.util.Iterator;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * This class wraps a list of problems with the input, so that the user
 * can get a list of problems together instead of finding and fixing them one 
 * by one.
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class InvalidInputException extends IOException {
 
  private static final long serialVersionUID = 1L;
  private List<IOException> problems;
  
  /**
   * Create the exception with the given list.
   * The first element of the list is used as the init cause value.
   * @param probs the list of problems to report. this list is not copied.
   */
  public InvalidInputException(List<IOException> probs) {
    problems = probs;
    if (!probs.isEmpty()) {
      initCause(probs.get(0));
    }
  }
  
  /**
   * Get the complete list of the problems reported.
   * @return the list of problems, which must not be modified
   */
  public List<IOException> getProblems() {
    return problems;
  }
  
  /**
   * Get a summary message of the problems found.
   * @return the concatenated messages from all of the problems.
   */
  public String getMessage() {
    StringBuffer result = new StringBuffer();
    Iterator<IOException> itr = problems.iterator();
    while(itr.hasNext()) {
      result.append(itr.next().getMessage());
      if (itr.hasNext()) {
        result.append("\n");
      }
    }
    return result.toString();
  }
}
