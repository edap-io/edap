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

package io.edap.log.converter;

import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;

public class BaseTextConverter implements TextConverter {

    private final byte[] bytes;

    public BaseTextConverter(String text) {
        this(text, null);
    }

    public BaseTextConverter(String text, String nextText) {
        if (StringUtil.isEmpty(nextText)) {
            bytes = text.getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = (text + nextText).getBytes(StandardCharsets.UTF_8);
        }
    }
    @Override
    public void convertTo(ByteArrayBuilder out, String s) {
        out.append(bytes);
    }
}
