/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.util;

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoFieldInfo;
import io.edap.util.TimeUtil;
import org.objectweb.asm.MethodVisitor;

import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.visitMethod;
import static org.objectweb.asm.Opcodes.*;

public class ProtoAsmUtil {

    private ProtoAsmUtil() {}

    public static String visitGetFieldValue(MethodVisitor mv, ProtoFieldInfo pfi, String pojoName,
                                            String pojoCodecName, int pojoSeq, String rType) {
        String type = rType;
        if (pfi.hasGetAccessed) {
            mv.visitVarInsn(ALOAD, pojoSeq);
            if (pfi.getMethod != null) {
                visitMethod(mv, INVOKEVIRTUAL, pojoName, pfi.getMethod.getName(),
                        "()" + rType, false);
                String timeUtil = toInternalName(TimeUtil.class.getName());
                if ("Ljava/util/Date;".equals(rType)) {
                    visitMethod(mv, INVOKESTATIC, timeUtil, "timeMillis", "(Ljava/util/Date;)J", false);
                    type = "J";
                } else if ("Ljava/util/Calendar;".equals(rType)) {
                    visitMethod(mv, INVOKESTATIC, timeUtil, "timeMillis", "(Ljava/util/Calendar;)J", false);
                    type = "J";
                } else if ("Ljava/time/LocalDateTime;".equals(rType)) {
                    visitMethod(mv, INVOKESTATIC, timeUtil, "timeMillis", "(Ljava/time/LocalDateTime;)J", false);
                    type = "J";
                }
            } else {
                mv.visitFieldInsn(GETFIELD, pojoName, pfi.field.getName(), rType);
            }
        } else {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F",
                    "Ljava/lang/reflect/Field;");
            mv.visitVarInsn(ALOAD, pojoSeq);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field",
                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);

            switch (rType) {
                case "I":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                    type = "Ljava/lang/Integer;";
                    break;
                case "Z":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    type = "Ljava/lang/Boolean;";
                    break;
                case "D":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                    type = "Ljava/lang/Double;";
                    break;
                case "F":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
                    type = "Ljava/lang/Float;";
                    break;
                case "J":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                    type = "Ljava/lang/Long;";
                    break;
                default:
                    mv.visitTypeInsn(CHECKCAST, toInternalName(pfi.field.getType().getName()));
            }

        }
        return type;
    }
}
