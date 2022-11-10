/*
 * Copyright 2022 The edap Project
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

package io.edap.protobuf.idl.serviceparser;

import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.ServiceParser;
import io.edap.protobuf.wire.Proto;

import java.util.Map;

/**
 * 解析java源代码，根据相关过滤来为接口或者实现类生成prot服务描述对象
 */
public class SrcServiceParser implements ServiceParser {
    @Override
    public Map<String, Proto> parseServices(BuildOption buildeOption) {
        return null;
    }
}
