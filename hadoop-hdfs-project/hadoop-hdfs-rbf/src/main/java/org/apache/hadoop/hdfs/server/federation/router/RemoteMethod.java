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
package org.apache.hadoop.hdfs.server.federation.router;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.CacheDirectiveInfo;
import org.apache.hadoop.hdfs.protocol.ClientProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Determines the remote client protocol method and the parameter list for a
 * specific location.
 */
public class RemoteMethod {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteMethod.class);


  /** List of parameters: static and dynamic values, matchings types. */
  private final Object[] params;
  /** List of method parameters types, matches parameters. */
  private final Class<?>[] types;
  /** Class of the protocol for the method. */
  private final Class<?> protocol;
  /** String name of the ClientProtocol method. */
  private final String methodName;

  /**
   * Create a remote method generator for the ClientProtocol with no parameters.
   *
   * @param method The string name of the protocol method.
   */
  public RemoteMethod(String method) {
    this(ClientProtocol.class, method);
  }

  /**
   * Create a method with no parameters.
   *
   * @param proto Protocol of the method.
   * @param method The string name of the ClientProtocol method.
   */
  public RemoteMethod(Class<?> proto, String method) {
    this.params = null;
    this.types = null;
    this.methodName = method;
    this.protocol = proto;
  }

  /**
   * Create a remote method generator for the ClientProtocol.
   *
   * @param method The string name of the ClientProtocol method.
   * @param pTypes A list of types to use to locate the specific method.
   * @param pParams A list of parameters for the method. The order of the
   *          parameter list must match the order and number of the types.
   *          Parameters are grouped into 2 categories:
   *          <ul>
   *          <li>Static parameters that are immutable across locations.
   *          <li>Dynamic parameters that are determined for each location by a
   *          RemoteParam object. To specify a dynamic parameter, pass an
   *          instance of RemoteParam in place of the parameter value.
   *          </ul>
   * @throws IOException If the types and parameter lists are not valid.
   */
  public RemoteMethod(String method, Class<?>[] pTypes, Object... pParams)
      throws IOException {
    this(ClientProtocol.class, method, pTypes, pParams);
  }

  /**
   * Creates a remote method generator.
   *
   * @param proto Protocol of the method.
   * @param method The string name of the ClientProtocol method.
   * @param pTypes A list of types to use to locate the specific method.
   * @param pParams A list of parameters for the method. The order of the
   *          parameter list must match the order and number of the types.
   *          Parameters are grouped into 2 categories:
   *          <ul>
   *          <li>Static parameters that are immutable across locations.
   *          <li>Dynamic parameters that are determined for each location by a
   *          RemoteParam object. To specify a dynamic parameter, pass an
   *          instance of RemoteParam in place of the parameter value.
   *          </ul>
   * @throws IOException If the types and parameter lists are not valid.
   */
  public RemoteMethod(Class<?> proto, String method, Class<?>[] pTypes,
      Object... pParams) throws IOException {

    if (pParams.length != pTypes.length) {
      throw new IOException("Invalid parameters for method " + method);
    }

    this.protocol = proto;
    this.params = pParams;
    this.types = Arrays.copyOf(pTypes, pTypes.length);
    this.methodName = method;
  }

  /**
   * Get the interface/protocol for this method. For example, ClientProtocol or
   * NamenodeProtocol.
   *
   * @return Protocol for this method.
   */
  public Class<?> getProtocol() {
    return this.protocol;
  }

  /**
   * Get the represented java method.
   *
   * @return Method
   * @throws IOException If the method cannot be found.
   */
  public Method getMethod() throws IOException {
    try {
      if (types != null) {
        return protocol.getDeclaredMethod(methodName, types);
      } else {
        return protocol.getDeclaredMethod(methodName);
      }
    } catch (NoSuchMethodException e) {
      // Re-throw as an IOException
      LOG.error("Cannot get method {} with types {} from {}",
          methodName, Arrays.toString(types), protocol.getSimpleName(), e);
      throw new IOException(e);
    } catch (SecurityException e) {
      LOG.error("Cannot access method {} with types {} from {}",
          methodName, Arrays.toString(types), protocol.getSimpleName(), e);
      throw new IOException(e);
    }
  }

  /**
   * Get the calling types for this method.
   *
   * @return An array of calling types.
   */
  public Class<?>[] getTypes() {
    return Arrays.copyOf(this.types, this.types.length);
  }

  /**
   * Generate a list of parameters for this specific location using no context.
   *
   * @return A list of parameters for the method customized for the location.
   */
  public Object[] getParams() {
    return this.getParams(null);
  }

  /**
   * Get the name of the method.
   *
   * @return Name of the method.
   */
  public String getMethodName() {
    return this.methodName;
  }

  /**
   * Generate a list of parameters for this specific location. Parameters are
   * grouped into 2 categories:
   * <ul>
   * <li>Static parameters that are immutable across locations.
   * <li>Dynamic parameters that are determined for each location by a
   * RemoteParam object.
   * </ul>
   *
   * @param context The context identifying the location.
   * @return A list of parameters for the method customized for the location.
   */
  public Object[] getParams(RemoteLocationContext context) {
    if (this.params == null) {
      return new Object[] {};
    }
    Object[] objList = new Object[this.params.length];
    for (int i = 0; i < this.params.length; i++) {
      Object currentObj = this.params[i];
      if (currentObj instanceof RemoteParam) {
        RemoteParam paramGetter = (RemoteParam) currentObj;
        // Map the parameter using the context
        if (this.types[i] == CacheDirectiveInfo.class) {
          CacheDirectiveInfo path =
              (CacheDirectiveInfo) paramGetter.getParameterForContext(context);
          objList[i] = new CacheDirectiveInfo.Builder(path)
              .setPath(new Path(context.getDest())).build();
        } else {
          objList[i] = paramGetter.getParameterForContext(context);
        }
      } else {
        objList[i] = currentObj;
      }
    }
    return objList;
  }

  @Override
  public String toString() {
    return new StringBuilder()
        .append(this.protocol.getSimpleName())
        .append("#")
        .append(this.methodName)
        .append("(")
        .append(Arrays.deepToString(this.params))
        .append(")")
        .toString();
  }
}
