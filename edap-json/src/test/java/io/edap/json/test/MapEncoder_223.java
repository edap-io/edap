/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.json.test;

import io.edap.json.JsonCodecRegister;
import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;
import io.edap.json.MapEncoder;
import io.edap.json.test.model.DemoOneString;

import java.util.Map;

public class MapEncoder_223 implements MapEncoder<String, DemoOneString> {

    JsonEncoder<DemoOneString> valueEncoder;

    public MapEncoder_223() {
        valueEncoder = JsonCodecRegister.instance().getEncoder(DemoOneString.class);
    }

    @Override
    public void encode(JsonWriter writer, Map<String, DemoOneString> map) {
        JsonEncoder<DemoOneString> encoder = valueEncoder;
        boolean start = false;
        for (Map.Entry<String, DemoOneString> entry : map.entrySet()) {
            if (!start) {
                writer.write((byte)'{');
                start = true;
            } else {
                writer.write((byte)',');

            }
            writer.write(String.valueOf(entry.getKey()));
            writer.write((byte)':');
            encoder.encode(writer, entry.getValue());
        }
        writer.write((byte)'}');
    }
}
