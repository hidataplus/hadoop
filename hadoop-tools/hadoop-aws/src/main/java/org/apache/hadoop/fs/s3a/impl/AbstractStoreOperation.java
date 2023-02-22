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

package org.apache.hadoop.fs.s3a.impl;

import org.apache.hadoop.fs.store.audit.AuditSpan;

import static org.apache.hadoop.thirdparty.com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class of operations in the store.
 * An operation is something which executes against the context to
 * perform a single function.
 */
public abstract class AbstractStoreOperation {

  /**
   * Store context.
   */
  private final StoreContext storeContext;

  /**
   * Audit Span.
   */
  private AuditSpan auditSpan;

  /**
   * Constructor.
   * Picks up the active audit span from the store context and
   * stores it for later.
   * @param storeContext store context.
   */
  protected AbstractStoreOperation(final StoreContext storeContext) {
    this(storeContext, storeContext.getActiveAuditSpan());
  }

  /**
   * Constructor.
   * @param storeContext store context.
   * @param auditSpan active span
   */
  protected AbstractStoreOperation(final StoreContext storeContext,
      final AuditSpan auditSpan) {
    this.storeContext = checkNotNull(storeContext);
    this.auditSpan = checkNotNull(auditSpan);
  }

  /**
   * Get the store context.
   * @return the context.
   */
  public final StoreContext getStoreContext() {
    return storeContext;
  }

  /**
   * Get the audit span this object was created with.
   * @return the current span
   */
  public AuditSpan getAuditSpan() {
    return auditSpan;
  }

  /**
   * Activate the audit span.
   */
  public void activateAuditSpan() {
    auditSpan.activate();
  }
}
