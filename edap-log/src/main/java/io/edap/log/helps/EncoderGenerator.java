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

import io.edap.log.AbstractEncoder;
import io.edap.log.Encoder;
import io.edap.log.converter.*;
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

    static final String PARENT_NAME = toInternalName(AbstractEncoder.class.getName());

    static final String BUILDER_NAME = toInternalName(ByteArrayBuilder.class.getName());

    static final String TEXT_CONV_FACTORY = toInternalName(TextConverterFactory.class.getName());

    static final String TEXT_CONV_NAME = toInternalName(TextConverter.class.getName());

    private String format;

    private ClassWriter cw;

    private final String encoderName;

    public EncoderGenerator(String format) {
        this.format = format;
        this.encoderName = toInternalName(getEncoderName(format));
    }

    public static String getEncoderName(String format) {
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


        FieldVisitor fvPattern = cw.visitField(ACC_PRIVATE | ACC_FINAL, "pattern",
                "Ljava/lang/String;", null, null);
        fvPattern.visitEnd();

        MethodVisitor cinitMethod = createCinitMethod();

        visitEncodeMethod(cinitMethod);

        //lambda的线程变量
        visitThreadLocalBuilderMethod();

        visitInitMethod();

        cinitMethod.visitInsn(RETURN);
        cinitMethod.visitMaxs(4, 0);
        cinitMethod.visitEnd();

        cw.visitEnd();

        classInfo.clazzName = encoderName;
        classInfo.clazzBytes = cw.toByteArray();
        return classInfo;
    }

    private void visitEncodeMethod(MethodVisitor cinitMethod) throws ParseException {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "encode",
                "(Ljava/io/OutputStream;Lio/edap/log/LogEvent;)V",
                null, null);
        mv.visitCode();

        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();

        mv.visitTryCatchBlock(label0, label1, label2, "java/lang/Exception");
        mv.visitFieldInsn(GETSTATIC, encoderName, "LOCAL_BYTE_ARRAY_BUILDER",
                "Ljava/lang/ThreadLocal;");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/ThreadLocal", "get",
                "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, BUILDER_NAME);
        mv.visitVarInsn(ASTORE, 3);

        EncoderPatternParser patternParser = new EncoderPatternParser(format);
        List<EncoderPatternToken> tokens = patternParser.parse();
        int size = tokens.size();
        EncoderPatternToken token;
        for (int i=0;i<size;i++) {
            token = tokens.get(i);
            if (token.getType() == TEXT) {
                String converterName = getConverterName(TEXT_CONV_NAME, token.getPattern(), null);
                FieldVisitor fv1 = cw.visitField(ACC_PRIVATE | ACC_STATIC,
                        converterName, "L" + TEXT_CONV_NAME + ";", null, null);
                fv1.visitEnd();
                visitTextConverterInit(cinitMethod, token.getPattern());

                mv.visitFieldInsn(GETSTATIC, encoderName, converterName,
                        "L" + TEXT_CONV_NAME  + ";");
                mv.visitVarInsn(ALOAD, 3);
                mv.visitInsn(ACONST_NULL);
                mv.visitMethodInsn(INVOKEINTERFACE, TEXT_CONV_NAME, "convertTo",
                        "(L" + BUILDER_NAME + ";Ljava/lang/Object;)V", true);
                continue;
            }
            Class converterCls = getKeywordConverter(token.getKeyword());
            String converterType = toInternalName(converterCls.getName());
            String nextText = null;
            if (i < size - 1) {
                EncoderPatternToken nextToken = tokens.get(i+1);
                if (nextToken.getType() == TEXT) {
                    nextText = nextToken.getPattern();
                    i++;
                }
            }
            String convName = getConverterName(converterCls.getName(), token.getPattern(), nextText);
            FieldVisitor fv1 = cw.visitField(ACC_PRIVATE | ACC_STATIC,
                    convName, "L" + converterType + ";", null, null);
            fv1.visitEnd();
            visitConverterInit(cinitMethod, converterCls.getName(), token.getPattern(), nextText);

            visitFuncConvertBlock(mv, converterCls, token.getPattern(), nextText);
        }

        mv.visitLabel(label0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, BUILDER_NAME, "writeTo",
                "(Ljava/io/OutputStream;)V", false);
        mv.visitLabel(label1);
        Label label3 = new Label();
        mv.visitJumpInsn(GOTO, label3);
        mv.visitLabel(label2);
        mv.visitFrame(Opcodes.F_FULL, 4, new Object[] {encoderName, "java/io/OutputStream",
                "io/edap/log/LogEvent", BUILDER_NAME}, 1, new Object[] {"java/lang/Exception"});
        mv.visitVarInsn(ASTORE, 4);
        mv.visitLdcInsn("writeTo error");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitMethodInsn(INVOKESTATIC, "io/edap/log/helpers/Util", "printError",
                "(Ljava/lang/String;Ljava/lang/Throwable;)V", false);
        mv.visitLabel(label3);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 5);
        mv.visitEnd();
    }

    private void visitFuncConvertBlock(MethodVisitor mv, Class converterCls, String pattern,
                                       String nextText) {
        String convName = getConverterName(converterCls.getName(), pattern, nextText);
        String convType = toInternalName(converterCls.getName());
        mv.visitFieldInsn(GETSTATIC, encoderName, convName, "L" + convType + ";");
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, convType, "convertTo",
                "(L" + BUILDER_NAME + ";Lio/edap/log/LogEvent;)V", false);

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
        String converterName = getConverterName(clazzName, format, nextText);
        mv.visitMethodInsn(INVOKESPECIAL, toInternalName(clazzName), "<init>",
                "(Ljava/lang/String;" + nextTextDesc + ")V", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, converterName,
                "L" + toInternalName(clazzName) + ";");
    }

    private void visitTextConverterInit(MethodVisitor mv, String text) {
        String converterName = getConverterName(TEXT_CONV_NAME, text, null);
        mv.visitLdcInsn(stringToInternal(text));
        mv.visitMethodInsn(INVOKESTATIC, TEXT_CONV_FACTORY, "getTextConverter",
                "(Ljava/lang/String;)L" + TEXT_CONV_NAME + ";", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, converterName, "L" + TEXT_CONV_NAME + ";");
    }

    private void visitInitMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "()V",
                false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(stringToInternal(format));
        mv.visitFieldInsn(PUTFIELD, encoderName, "pattern", "Ljava/lang/String;");
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
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

        return mv;
    }

    private String getConverterName(String clsName, String format, String nextText) {
        StringBuilder sb = new StringBuilder();
        sb.append("CONVERTER_").append(md5(clsName + "_" + format + "_" + nextText));
        return sb.toString();
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
