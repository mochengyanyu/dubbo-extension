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

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MethodCallerInterceptor {

    // 缓存: 真实对象类名 + 方法名 + 参数类型签名 -> Method
    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();

    @RuntimeType
    public static Object call(@AllArguments Object[] args) throws Throwable {
        // call(Object target, String methodName, Class<?>[] parameterTypes, Object[] arguments)
        Object target = args[0];
        String methodName = (String) args[1];
        Class<?>[] parameterTypes = (Class<?>[]) args[2];
        Object[] arguments = (Object[]) args[3];

        if (target == null) {
            throw new IllegalArgumentException("target must not be null");
        }

        // 真实对象类型，而不是代理类型
        Class<?> targetClass = target.getClass();
        String key = targetClass.getName() + "#" + methodName + "(" + Arrays.toString(parameterTypes) + ")";

        Method method = METHOD_CACHE.computeIfAbsent(key, k -> {
            try {
                return targetClass.getMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException(
                        "No method " + methodName + "(" + Arrays.toString(parameterTypes) + ") on "
                                + targetClass.getName(),
                        e);
            }
        });

        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            // 拆包，抛出业务方法的真实异常
            throw e.getCause();
        }
    }
}
