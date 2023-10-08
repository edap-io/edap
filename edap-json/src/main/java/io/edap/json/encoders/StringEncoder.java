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

package io.edap.json.encoders;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

/**
 * 字符串类型的Json编码器
 */
public class StringEncoder implements JsonEncoder<String> {
    @Override
    public void encode(JsonWriter writer, String obj) {
        writer.write(obj);
    }
}
