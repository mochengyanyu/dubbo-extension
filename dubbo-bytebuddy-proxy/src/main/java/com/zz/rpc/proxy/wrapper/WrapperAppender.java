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

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.Implementation.Context;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import java.lang.reflect.Method;
import java.util.List;

public class WrapperAppender implements ByteCodeAppender {
    private final Class<?> targetClass;
    private final List<Method> methods;

    WrapperAppender(Class<?> targetClass, List<Method> methods) {
        this.targetClass = targetClass;
        this.methods = methods;
    }

    @Override
    public Size apply(MethodVisitor mv, Context context, MethodDescription instrumentedMethod) {
        // 局部变量表（invokeMethod 是实例方法）：
        //   0 = this
        //   1 = instance (Object)
        //   2 = methodName (String)
        //   3 = types (Class[])
        //   4 = args (Object[])
        // 每次调用前栈上的最大深度 = instance(1) + 参数槽位（long/double 占 2）
        int maxStack = 2;
        for (Method m : methods) {
            int argSlots = 0;
            for (Class<?> p : m.getParameterTypes()) {
                argSlots += (p == long.class || p == double.class) ? 2 : 1;
            }
            // instance(1) + 全部参数(argSlots) + args 引用(1) + 索引(1)
            int peak = 1 + argSlots + 2;
            maxStack = Math.max(maxStack, peak);
        }

        for (Method m : methods) {
            Label next = new Label();
            // if (!methodName.equals("xxx")) goto next
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitLdcInsn(m.getName());
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            mv.visitJumpInsn(Opcodes.IFEQ, next);

            // if (types.length != paramCount) goto next
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitInsn(Opcodes.ARRAYLENGTH);
            pushInt(mv, m.getParameterCount());
            mv.visitJumpInsn(Opcodes.IF_ICMPNE, next);

            // Target t = (Target) instance;
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(targetClass));

            // 逐个 push args[i] 并按参数类型转换/拆箱
            Class<?>[] ptypes = m.getParameterTypes();
            for (int i = 0; i < ptypes.length; i++) {
                mv.visitVarInsn(Opcodes.ALOAD, 4);
                pushInt(mv, i);
                mv.visitInsn(Opcodes.AALOAD);
                castOrUnbox(mv, ptypes[i]);
            }

            // 调用真实方法
            Class<?> declaring = m.getDeclaringClass();
            boolean isInterface = declaring.isInterface();
            mv.visitMethodInsn(
                    isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                    Type.getInternalName(declaring),
                    m.getName(),
                    Type.getMethodDescriptor(m),
                    isInterface);

            // 返回值装箱（void -> null）
            boxOrVoid(mv, m.getReturnType());
            mv.visitInsn(Opcodes.ARETURN);

            mv.visitLabel(next);
            // ============ 关键修复：为分支目标补上 stack map frame ============
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }

        // throw new NoSuchMethodException(methodName)
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/NoSuchMethodException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL, "java/lang/NoSuchMethodException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);

        return new Size(maxStack, instrumentedMethod.getStackSize());
    }

    // -------- 工具方法 --------
    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    /** Object -> 目标参数类型：引用 CHECKCAST，基本类型拆箱 */
    private static void castOrUnbox(MethodVisitor mv, Class<?> type) {
        if (!type.isPrimitive()) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(type));
            return;
        }
        String wrapper, method, desc;
        if (type == int.class) {
            wrapper = "java/lang/Integer";
            method = "intValue";
            desc = "()I";
        } else if (type == long.class) {
            wrapper = "java/lang/Long";
            method = "longValue";
            desc = "()J";
        } else if (type == double.class) {
            wrapper = "java/lang/Double";
            method = "doubleValue";
            desc = "()D";
        } else if (type == float.class) {
            wrapper = "java/lang/Float";
            method = "floatValue";
            desc = "()F";
        } else if (type == boolean.class) {
            wrapper = "java/lang/Boolean";
            method = "booleanValue";
            desc = "()Z";
        } else if (type == byte.class) {
            wrapper = "java/lang/Byte";
            method = "byteValue";
            desc = "()B";
        } else if (type == short.class) {
            wrapper = "java/lang/Short";
            method = "shortValue";
            desc = "()S";
        } else if (type == char.class) {
            wrapper = "java/lang/Character";
            method = "charValue";
            desc = "()C";
        } else {
            throw new IllegalStateException("Unknown primitive: " + type);
        }

        mv.visitTypeInsn(Opcodes.CHECKCAST, wrapper);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, wrapper, method, desc, false);
    }

    /** 返回值 -> Object：void 返回 null，基本类型装箱，引用类型原样返回 */
    private static void boxOrVoid(MethodVisitor mv, Class<?> type) {
        if (type == void.class) {
            mv.visitInsn(Opcodes.ACONST_NULL);
            return;
        }
        if (!type.isPrimitive()) {
            return;
        }

        String wrapper, method, desc;
        if (type == int.class) {
            wrapper = "java/lang/Integer";
            method = "valueOf";
            desc = "(I)Ljava/lang/Integer;";
        } else if (type == long.class) {
            wrapper = "java/lang/Long";
            method = "valueOf";
            desc = "(J)Ljava/lang/Long;";
        } else if (type == double.class) {
            wrapper = "java/lang/Double";
            method = "valueOf";
            desc = "(D)Ljava/lang/Double;";
        } else if (type == float.class) {
            wrapper = "java/lang/Float";
            method = "valueOf";
            desc = "(F)Ljava/lang/Float;";
        } else if (type == boolean.class) {
            wrapper = "java/lang/Boolean";
            method = "valueOf";
            desc = "(Z)Ljava/lang/Boolean;";
        } else if (type == byte.class) {
            wrapper = "java/lang/Byte";
            method = "valueOf";
            desc = "(B)Ljava/lang/Byte;";
        } else if (type == short.class) {
            wrapper = "java/lang/Short";
            method = "valueOf";
            desc = "(S)Ljava/lang/Short;";
        } else if (type == char.class) {
            wrapper = "java/lang/Character";
            method = "valueOf";
            desc = "(C)Ljava/lang/Character;";
        } else {
            throw new IllegalStateException("Unknown primitive: " + type);
        }

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, method, desc, false);
    }
}
