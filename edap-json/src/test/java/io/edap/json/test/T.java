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

import io.edap.io.ByteArrayBufOut;
import io.edap.json.JsonCodecRegister;
import io.edap.json.JsonDecoderGenerator;
import io.edap.json.JsonEncoder;
import io.edap.json.enums.DataType;
import io.edap.json.test.model.DemoOneString;
import io.edap.json.test.model.DemoPojo;
import io.edap.json.test.model.TableExpectInfo;
import io.edap.json.writer.ByteArrayJsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class T {

    public static void main(String[] args) throws IOException {

        JsonCodecRegister.instance().getEncoder(TableExpectInfo.class);
    }
}
