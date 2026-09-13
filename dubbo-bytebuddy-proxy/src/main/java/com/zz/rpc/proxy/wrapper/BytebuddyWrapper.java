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
package com.zz.rpc.proxy.wrapper;

import com.zz.rpc.proxy.MethodCaller;
import com.zz.rpc.proxy.MethodCallerInterceptor;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType.Loaded;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;


import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;

public class BytebuddyWrapper {

    public static MethodCaller getWrapper(Class<?> type) {
        ByteBuddy byteBuddy = new ByteBuddy();
        Loaded<Object> loaded = byteBuddy
                .subclass(Object.class)
                .implement(MethodCaller.class)
                .method(isDeclaredBy(MethodCaller.class)) // 只实现 MethodCaller 的方法
                .intercept(MethodDelegation.to(MethodCallerInterceptor.class)) // 委托给静态方法
                .make()
                .load(type.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER);
        try {
            return (MethodCaller) loaded.getLoaded().newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
