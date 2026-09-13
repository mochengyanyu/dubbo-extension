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

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public abstract class Wrapper {

    public abstract Object invokeMethod(Object instance, String methodName, Class<?>[] types, Object[] args)
            throws Throwable;

    // ---------------- 缓存：每个类只生成一次 Wrapper ----------------
    private static final Map<Class<?>, Wrapper> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong COUNTER = new AtomicLong();

    public static Wrapper getWrapper(Class<?> clazz) {
        return CACHE.computeIfAbsent(clazz, Wrapper::generate);
    }

    private static Wrapper generate(Class<?> clazz) {
        try {
            List<Method> methods = new ArrayList<>();
            for (Method m : clazz.getMethods()) {

                if (m.getDeclaringClass() == Object.class) {
                    continue; // 跳过 Object 方法
                }

                if (Modifier.isStatic(m.getModifiers())) {
                    continue; // 跳过 static 方法
                }

                methods.add(m);
            }

            String wrapperName = clazz.getName() + "$ByteBuddyWrapper" + COUNTER.incrementAndGet();

            Class<? extends Wrapper> wrapperClass = new ByteBuddy()
                    .subclass(Wrapper.class)
                    .name(wrapperName)
                    .method(ElementMatchers.named("invokeMethod"))
                    .intercept(new WrapperImplementation(clazz, methods))
                    .make()
                    .load(clazz.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
                    .getLoaded();

            return wrapperClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Generate Wrapper failed for " + clazz, e);
        }
    }
}
