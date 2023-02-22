/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
#ifndef FILE_DESCRIPTOR_H
#define FILE_DESCRIPTOR_H

#include <jni.h>
#include "org_apache_hadoop.h"

void fd_init(JNIEnv *env);
void fd_deinit(JNIEnv *env);

#ifdef UNIX
int fd_get(JNIEnv* env, jobject obj);
jobject fd_create(JNIEnv *env, int fd);
#endif

#ifdef WINDOWS
long fd_get(JNIEnv* env, jobject obj);
jobject fd_create(JNIEnv *env, long fd);
#endif

#endif
