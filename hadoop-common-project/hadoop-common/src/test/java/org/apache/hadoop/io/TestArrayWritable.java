/*
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

package org.apache.hadoop.io;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;


/** Unit tests for ArrayWritable */
public class TestArrayWritable {
  static class TextArrayWritable extends ArrayWritable {
    public TextArrayWritable() {
      super(Text.class);
    }
  }
	
  /**
   * If valueClass is undefined, readFields should throw an exception indicating
   * that the field is null. Otherwise, readFields should succeed.	
   */
  @Test
  public void testThrowUndefinedValueException() throws IOException {
    // Get a buffer containing a simple text array
    Text[] elements = {new Text("zero"), new Text("one"), new Text("two")};
    TextArrayWritable sourceArray = new TextArrayWritable();
    sourceArray.set(elements);

    // Write it to a normal output buffer
    DataOutputBuffer out = new DataOutputBuffer();
    DataInputBuffer in = new DataInputBuffer();
    sourceArray.write(out);

    // Read the output buffer with TextReadable. Since the valueClass is defined,
    // this should succeed
    TextArrayWritable destArray = new TextArrayWritable();
    in.reset(out.getData(), out.getLength());
    destArray.readFields(in);
    Writable[] destElements = destArray.get();
    assertTrue(destElements.length == elements.length);
    for (int i = 0; i < elements.length; i++) {
      assertEquals(destElements[i],elements[i]);
    }
  }
  
 /**
  * test {@link ArrayWritable} toArray() method 
  */
 @Test
  public void testArrayWritableToArray() {
    Text[] elements = {new Text("zero"), new Text("one"), new Text("two")};
    TextArrayWritable arrayWritable = new TextArrayWritable();
    arrayWritable.set(elements);
    Object array = arrayWritable.toArray();
  
    assertTrue("TestArrayWritable testArrayWritableToArray error!!! ", array instanceof Text[]);
    Text[] destElements = (Text[]) array;
  
    for (int i = 0; i < elements.length; i++) {
      assertEquals(destElements[i], elements[i]);
    }
  }
  
  /**
   * test {@link ArrayWritable} constructor with null
   */
  @Test(expected = IllegalArgumentException.class)
  public void testNullArgument() {
    new ArrayWritable((Class<? extends Writable>) null);
  }

  /**
   * test {@link ArrayWritable} constructor with {@code String[]} as a parameter
   */
  @Test
  public void testArrayWritableStringConstructor() {
    String[] original = { "test1", "test2", "test3" };
    ArrayWritable arrayWritable = new ArrayWritable(original);
    assertEquals("testArrayWritableStringConstructor class error!!!", 
        Text.class, arrayWritable.getValueClass());
    assertArrayEquals("testArrayWritableStringConstructor toString error!!!",
      original, arrayWritable.toStrings());
  }
  
}
