package io.edap.nio.util;

import org.objectweb.asm.*;

import static org.objectweb.asm.Opcodes.*;

public class DirectIoFactory {

    public static byte[] createDirectIOClass() {
        ClassWriter cw;
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, "EdapFastDirectIO",
                null, "java/lang/Object", new String[] {  });

        FieldVisitor fv = cw.visitField(ACC_STATIC, "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;", null, null);
        fv.visitEnd();

        MethodVisitor methodVisitor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(1, 1);
        methodVisitor.visitEnd();

        methodVisitor = cw.visitMethod(ACC_PUBLIC, "read", "(Ljava/io/FileDescriptor;JI)I", null, new String[] { "java/io/IOException" });
        methodVisitor.visitCode();
        methodVisitor.visitFieldInsn(GETSTATIC, "EdapFastDirectIO", "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;");
        //methodVisitor.visitTypeInsn(CHECKCAST, "io/edap/nio/DirectIO");
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(LLOAD, 2);
        methodVisitor.visitVarInsn(ILOAD, 4);
        methodVisitor.visitMethodInsn(INVOKEVIRTUAL, "sun/nio/ch/FileDispatcherImpl", "read", "(Ljava/io/FileDescriptor;JI)I", false);
        methodVisitor.visitInsn(IRETURN);
        methodVisitor.visitMaxs(5, 5);
        methodVisitor.visitEnd();

        methodVisitor = cw.visitMethod(ACC_PUBLIC, "write", "(Ljava/io/FileDescriptor;JI)I", null, new String[] { "java/io/IOException" });
        methodVisitor.visitCode();
        methodVisitor.visitInsn(ICONST_0);
        methodVisitor.visitInsn(IRETURN);
        methodVisitor.visitMaxs(1, 5);
        methodVisitor.visitEnd();

        methodVisitor = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/ClassNotFoundException");
        Label label3 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label3, "java/lang/InstantiationException");
        Label label4 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label4, null);
        Label label5 = new Label();
        methodVisitor.visitTryCatchBlock(label2, label5, label4, null);
        methodVisitor.visitInsn(ACONST_NULL);
        methodVisitor.visitFieldInsn(PUTSTATIC, "sun/nio/ch/FileDispatcherImpl", "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;");
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLdcInsn("sun.nio.ch.FileDispatcherImpl");
        methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        methodVisitor.visitVarInsn(ASTORE, 0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "io/edap/util/UnsafeUtil", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        methodVisitor.visitVarInsn(ASTORE, 1);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitTypeInsn(CHECKCAST, "sun/nio/ch/FileDispatcherImpl");
        methodVisitor.visitFieldInsn(PUTSTATIC, "sun/nio/ch/FileDispatcherImpl", "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;");
        methodVisitor.visitLabel(label1);
        Label label6 = new Label();
        methodVisitor.visitJumpInsn(GOTO, label6);
        methodVisitor.visitLabel(label2);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/ClassNotFoundException"});
        methodVisitor.visitVarInsn(ASTORE, 1);
        methodVisitor.visitTypeInsn(NEW, "java/lang/RuntimeException");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", false);
        methodVisitor.visitInsn(ATHROW);
        methodVisitor.visitLabel(label3);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/InstantiationException"});
        methodVisitor.visitVarInsn(ASTORE, 1);
        methodVisitor.visitTypeInsn(NEW, "java/lang/RuntimeException");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", false);
        methodVisitor.visitInsn(ATHROW);
        methodVisitor.visitLabel(label4);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
        methodVisitor.visitVarInsn(ASTORE, 2);
        methodVisitor.visitLabel(label5);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitInsn(ATHROW);
        methodVisitor.visitLabel(label6);
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(3, 3);
        methodVisitor.visitEnd();


        return cw.toByteArray();
    }

    public static byte[] createDirectIOLoader() {
        ClassWriter cw;
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, "DirectIOLoader",
                null, "java/lang/ClassLoader", null);

        FieldVisitor fv = cw.visitField(ACC_STATIC, "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;", null, null);
        fv.visitEnd();

        MethodVisitor methodVisitor = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/lang/ClassLoader;)V", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/ClassLoader", "<init>", "(Ljava/lang/ClassLoader;)V", false);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(2, 2);
        methodVisitor.visitEnd();

        methodVisitor = cw.visitMethod(ACC_PUBLIC, "define", "(Ljava/lang/String;[BII)Ljava/lang/Class;", null, null);
        methodVisitor.visitCode();
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitVarInsn(ILOAD, 3);
        methodVisitor.visitVarInsn(ILOAD, 4);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/ClassLoader", "defineClass", "(Ljava/lang/String;[BII)Ljava/lang/Class;", false);
        methodVisitor.visitInsn(ARETURN);
        methodVisitor.visitMaxs(5, 5);
        methodVisitor.visitEnd();

        methodVisitor = cw.visitMethod(ACC_PUBLIC, "write", "(Ljava/io/FileDescriptor;JI)I", null, new String[] { "java/io/IOException" });
        methodVisitor.visitCode();
        methodVisitor.visitInsn(ICONST_0);
        methodVisitor.visitInsn(IRETURN);
        methodVisitor.visitMaxs(1, 5);
        methodVisitor.visitEnd();

        methodVisitor = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        methodVisitor.visitCode();
        Label label0 = new Label();
        Label label1 = new Label();
        Label label2 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label2, "java/lang/ClassNotFoundException");
        Label label3 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label3, "java/lang/InstantiationException");
        Label label4 = new Label();
        methodVisitor.visitTryCatchBlock(label0, label1, label4, null);
        Label label5 = new Label();
        methodVisitor.visitTryCatchBlock(label2, label5, label4, null);
        methodVisitor.visitInsn(ACONST_NULL);
        methodVisitor.visitFieldInsn(PUTSTATIC, "sun/nio/ch/FileDispatcherImpl", "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;");
        methodVisitor.visitLabel(label0);
        methodVisitor.visitLdcInsn("sun.nio.ch.FileDispatcherImpl");
        methodVisitor.visitMethodInsn(INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        methodVisitor.visitVarInsn(ASTORE, 0);
        methodVisitor.visitVarInsn(ALOAD, 0);
        methodVisitor.visitMethodInsn(INVOKESTATIC, "io/edap/util/UnsafeUtil", "allocateInstance", "(Ljava/lang/Class;)Ljava/lang/Object;", false);
        methodVisitor.visitVarInsn(ASTORE, 1);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitTypeInsn(CHECKCAST, "sun/nio/ch/FileDispatcherImpl");
        methodVisitor.visitFieldInsn(PUTSTATIC, "sun/nio/ch/FileDispatcherImpl", "WIRTER", "Lsun/nio/ch/FileDispatcherImpl;");
        methodVisitor.visitLabel(label1);
        Label label6 = new Label();
        methodVisitor.visitJumpInsn(GOTO, label6);
        methodVisitor.visitLabel(label2);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/ClassNotFoundException"});
        methodVisitor.visitVarInsn(ASTORE, 1);
        methodVisitor.visitTypeInsn(NEW, "java/lang/RuntimeException");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", false);
        methodVisitor.visitInsn(ATHROW);
        methodVisitor.visitLabel(label3);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/InstantiationException"});
        methodVisitor.visitVarInsn(ASTORE, 1);
        methodVisitor.visitTypeInsn(NEW, "java/lang/RuntimeException");
        methodVisitor.visitInsn(DUP);
        methodVisitor.visitVarInsn(ALOAD, 1);
        methodVisitor.visitMethodInsn(INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/Throwable;)V", false);
        methodVisitor.visitInsn(ATHROW);
        methodVisitor.visitLabel(label4);
        methodVisitor.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
        methodVisitor.visitVarInsn(ASTORE, 2);
        methodVisitor.visitLabel(label5);
        methodVisitor.visitVarInsn(ALOAD, 2);
        methodVisitor.visitInsn(ATHROW);
        methodVisitor.visitLabel(label6);
        methodVisitor.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        methodVisitor.visitInsn(RETURN);
        methodVisitor.visitMaxs(3, 3);
        methodVisitor.visitEnd();


        return cw.toByteArray();
    }

    public static DirectIOLoader2 createDirectIOClassLoader(Class jvmIOClass) {
        DirectIOLoader2 loader = new DirectIOLoader2(jvmIOClass.getClassLoader());
        return loader;
    }


}
