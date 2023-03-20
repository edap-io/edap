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

package io.edap.json.test;

import io.edap.json.JsonCodecRegister;
import io.edap.json.JsonDecoderGenerator;
import io.edap.json.enums.DataType;
import io.edap.json.test.model.DemoOneString;
import io.edap.json.test.model.DemoPojo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class T {

    public static void main(String[] args) throws IOException {
        String str = "中文";
        char c = str.charAt(0);
        byte[] bs = str.getBytes(StandardCharsets.UTF_8);
        System.out.println("9=" + (int)'9');
        System.out.println("a=" + (int)'a');
        System.out.println("A=" + (int)'A');
        System.out.println("d=" + (int)'d');
        System.out.println("c=" + (char)bs[0]);
        System.out.println("c=" + (int)c);
        System.out.println("b=" + (byte)c);
        System.out.println("0xdecaf=" + Integer.parseInt("decaf", 16));

        int value = 5;
        System.out.println("value=" + ((value << 3) + (value << 1)));
        System.out.println("value=" + ((value << 6) + (value << 5) + (value << 2)));

        long start = System.currentTimeMillis();
        JsonCodecRegister.instance().getDecoder(DemoPojo.class, DataType.BYTE_ARRAY);
        System.out.println("time=" + (System.currentTimeMillis() - start));

        for (int i=0;i<10;i++) {
            start = System.nanoTime();
            JsonDecoderGenerator generator = new JsonDecoderGenerator(DemoOneString.class, DataType.BYTE_ARRAY);
            generator.getClassInfo();
            System.out.println("time" + i + ": " + (System.nanoTime() - start));
        }

        start = System.currentTimeMillis();
        JsonCodecRegister.instance().getDecoder(DemoOneString.class, DataType.BYTE_ARRAY);
        System.out.println("time=" + (System.currentTimeMillis() - start));
        System.out.println("unicode:" + Integer.toHexString("中".charAt(0)));
        System.out.println("unicode:" + Integer.toHexString("文".charAt(0)));
        // 2,4,8,16,32,64,
    }
}
