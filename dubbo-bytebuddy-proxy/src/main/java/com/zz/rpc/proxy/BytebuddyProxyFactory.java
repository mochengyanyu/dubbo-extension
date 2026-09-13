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

import com.zz.rpc.proxy.wrapper.Wrapper;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.DynamicType.Loaded;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.proxy.AbstractProxyFactory;
import org.apache.dubbo.rpc.proxy.AbstractProxyInvoker;
import org.apache.dubbo.rpc.proxy.InvokerInvocationHandler;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.not;

public class BytebuddyProxyFactory extends AbstractProxyFactory {

    /**
     * 这个代理对象,用于客户端的
     * @param invoker
     * @param types
     * @return
     * @param <T>
     */
    @Override
    public <T> T getProxy(Invoker<T> invoker, Class<?>[] types) {
        ByteBuddy byteBuddy = new ByteBuddy();
        Class<T> anInterface = invoker.getInterface();
        ClassLoader classLoader = invoker.getInterface().getClassLoader();
        Loaded<Object> load = byteBuddy
                .subclass(Object.class)
                .implement(anInterface)
                .method(not(isDeclaredBy(Object.class)))
                .intercept(InvocationHandlerAdapter.of(new InvokerInvocationHandler(invoker)))
                .make()
                .load(classLoader, Default.WRAPPER);
        try {
            T tProxy = (T) load.getLoaded().newInstance();
            return tProxy;
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 这个代理对象,用于服务端的
     * @param proxy
     * @param type
     * @param url
     * @return
     * @param <T>
     * @throws RpcException
     */
    @Override
    public <T> Invoker<T> getInvoker(T proxy, Class<T> type, URL url) throws RpcException {
        // MethodCaller methodCaller = BytebuddyWrapper.getWrapper(type);
        Wrapper wrapper = Wrapper.getWrapper(type);
        return new AbstractProxyInvoker<T>(proxy, type, url) {
            @Override
            protected Object doInvoke(T proxy, String methodName, Class<?>[] parameterTypes, Object[] arguments)
                    throws Throwable {
                return wrapper.invokeMethod(proxy, methodName, parameterTypes, arguments);
            }
        };
    }
}
