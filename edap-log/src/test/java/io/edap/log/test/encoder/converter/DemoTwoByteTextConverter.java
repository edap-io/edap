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

package io.edap.log.test.encoder.converter;

import io.edap.log.converter.TextConverter;
import io.edap.log.helps.ByteArrayBuilder;

import java.nio.charset.StandardCharsets;

public class DemoTwoByteTextConverter implements TextConverter {

    private static final byte b1;
    private static final byte b2;

    static {
        byte[] bytes = "b2".getBytes(StandardCharsets.UTF_8);
        b1 = bytes[0];
        b2 = bytes[1];
    }

    public DemoTwoByteTextConverter() {

    }


    @Override
    public void convertTo(ByteArrayBuilder out, String s) {
        out.append(b1, b2);
    }
}
