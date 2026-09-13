/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zz.rpc.proxy;

public interface MethodCaller {
    /**
     * 根据方法名和参数动态调用目标实例的方法
     * @param target  真实服务对象
     * @param methodName 方法名
     * @param parameterTypes 参数类型数组（用于精确匹配）
     * @param arguments 参数值
     * @return 方法返回值
     * @throws Throwable 调用过程中抛出的异常
     */
    Object call(Object target, String methodName, Class<?>[] parameterTypes, Object[] arguments) throws Throwable;
}
