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

import eje.com.jsoniter.benchmark.with_map_field.TestObject;
import io.edap.io.ByteArrayBufOut;
import io.edap.json.*;
import io.edap.json.enums.DataType;
import io.edap.json.test.model.DemoOneString;
import io.edap.json.test.model.DemoPojo;
import io.edap.json.test.model.SimplePojo;
import io.edap.json.test.model.TableExpectInfo;
import io.edap.json.writer.ByteArrayJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;

public class T {

    public static void main(String[] args) throws IOException {
        CollectionHolder h = new CollectionHolder();
        h.items = Arrays.asList("a", "b");
        String json = Eson.toJsonString(h);
        System.out.println(json);

        Wrapper w = new Wrapper();
        w.setData(new HashMap<>());  // 或任何实例
        json = Eson.toJsonString(w);  // StackOverflowError

        System.out.println("----------------------");
        System.out.println(json);
// 情情形 2：裸 Object 实例
        Eson.toJsonString(new Object());
        System.out.println("----------------------");
        System.out.println(json);
    }
}
