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

package io.edap.data.jdbc.test;

import edao.io.edap.data.jdbc.test.entity.DemoAllTypeJdbcEntityDao2;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * @date : 2019/12/25
 */
public class Asm {
    public static void main(String[] args) throws FileNotFoundException, IOException {
        int flags = ClassReader.SKIP_DEBUG;

        MethodVisitor mv;


        System.out.println("short Max=" + Short.MAX_VALUE);

        String clsPath = DemoAllTypeJdbcEntityDao2.class.getName();
        ClassReader cr;
        if (clsPath.endsWith(".class") || clsPath.indexOf('\\') > -1
                || clsPath.indexOf('/') > -1) {
            cr = new ClassReader(new FileInputStream(clsPath));
        } else {
            cr = new ClassReader(clsPath);
        }
        cr.accept(new TraceClassVisitor(null, new ASMifier(), new PrintWriter(
                System.out)), flags);
    }
}