/*
 * Copyright 2023 The edap Project
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

package io.edap.log.helps;

import io.edap.log.Encoder;
import io.edap.util.StringUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.lang.reflect.Method;
import java.text.ParseException;
import java.util.List;

import static io.edap.log.helps.EncoderPatternToken.TokenType.ENCODER_FUNC;
import static io.edap.log.helps.EncoderPatternToken.TokenType.TEXT;
import static io.edap.log.util.LogUtil.getKeywordConverter;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.visitMethod;
import static io.edap.util.CryptUtil.md5;
import static org.objectweb.asm.Opcodes.*;

public class EncoderGenerator {

    static final String IFACE_NAME = toInternalName(Encoder.class.getName());

    static final String PARENT_NAME = toInternalName(Object.class.getName());

    static final String BUILDER_NAME = toInternalName(ByteArrayBuilder.class.getName());

    private String format;

    private ClassWriter cw;

    private final String encoderName;

    public EncoderGenerator(String format) {
        this.format = format;
        this.encoderName = toInternalName(getEncoderName(format));
    }

    private String getEncoderName(String format) {
        StringBuilder sb = new StringBuilder("io.edap.log.encoder.gen.FormatEncoder");
        sb.append(md5(format));
        return sb.toString();
    }

    public GeneratorClassInfo getClassInfo() throws ParseException {
        GeneratorClassInfo classInfo = new GeneratorClassInfo();

        String[] ifaceName = new String[]{IFACE_NAME};

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, encoderName,
                null, PARENT_NAME, ifaceName);


        MethodVisitor cinitMethod = createCinitMethod();

        EncoderPatternParser patternParser = new EncoderPatternParser(format);
        List<EncoderPatternToken> tokens = patternParser.parse();
        int size = tokens.size();
        EncoderPatternToken token;
        for (int i=0;i<size;i++) {
            token = tokens.get(i);
            if (token.getType() == ENCODER_FUNC) {
                Class converterCls = getKeywordConverter(token.getKeyword());
                if (i < size - 1) {
                    EncoderPatternToken nextToken = tokens.get(i+1);
                    if (nextToken.getType() == TEXT) {

                        i++;
                    }
                }
            }
        }

        //lambda的线程变量
        visitThreadLocalBuilderMethod();



        return classInfo;
    }

    private void visitConverterInit(MethodVisitor mv, String clazzName, String format, String nextText) {
        mv.visitTypeInsn(NEW, toInternalName(clazzName));
        mv.visitInsn(DUP);
        mv.visitLdcInsn(format);
        String nextTextDesc = "";
        if (!StringUtil.isEmpty(nextText)) {
            mv.visitLdcInsn(stringToInternal(nextText));
            nextTextDesc = "Ljava/lang/String;";
        }
        mv.visitMethodInsn(INVOKESPECIAL, toInternalName(clazzName), "<init>",
                "(Ljava/lang/String;" + nextTextDesc + ")V", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, "CACHE_DATE_CONVERT",
                "L" + toInternalName(clazzName) + ";");
    }

     public String stringToInternal(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<text.length();i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else {
                sb.append("\\u");
                String high = Integer.toHexString(c >>> 8);
                if (high.length() == 1) {
                    sb.append('0');
                }
                sb.append(high);
                String low = Integer.toHexString(c & 0xFF);
                if (low.length() == 1) {
                    sb.append('0');
                }
                sb.append(low);
            }
        }
        return sb.toString();
     }

    private MethodVisitor createCinitMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitInvokeDynamicInsn("get", "()Ljava/util/function/Supplier;",
                new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;" +
                                "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;" +
                                "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                                "Ljava/lang/invoke/CallSite;", false),
                new Object[]{Type.getType("()Ljava/lang/Object;"),
                        new Handle(Opcodes.H_INVOKESTATIC, "io/edap/log/test/encoder/DemoEncoder",
                                "lambda$static$0",
                                "()L" + BUILDER_NAME +";", false),
                        Type.getType("()L\" + BUILDER_NAME +\";")});
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/ThreadLocal", "withInitial",
                "(Ljava/util/function/Supplier;)Ljava/lang/ThreadLocal;", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName,
                "LOCAL_BYTE_ARRAY_BUILDER", "Ljava/lang/ThreadLocal;");

        return mv;
    }

    private void visitThreadLocalBuilderMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC, "lambda$static$0",
                "()L" + BUILDER_NAME + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, BUILDER_NAME);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, BUILDER_NAME, "<init>", "()V", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }
}
