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

import io.edap.json.AbstractEncoder;
import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;
import io.edap.json.test.model.DemoFieldList;

import java.util.Iterator;
import java.util.List;

public class DemoFieldListEncoder extends AbstractEncoder implements JsonEncoder<DemoFieldList> {
    private static final byte[] KBS_FIELD1 = ",\"field1\":null".getBytes();

    public DemoFieldListEncoder() {
    }

    public void encode(JsonWriter var1, DemoFieldList var2) {
        var1.write((byte)'{');
        int start = 1;
        if (var2.getList() != null) {
            var1.writeField(KBS_FIELD1, start, 10);
            var1.write((byte)'[');
            List<Integer> list = var2.getList();
            if (list.size() > 0) {
                var1.write(list.get(0));
            }
            for(int i=1;i<list.size();i++) {
                var1.write((byte)',');
                var1.write(list.get(i));
            }
            var1.write(']');
            start = 0;
        }

        var1.write('}');
    }
}
