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
import io.edap.protobuf.idl.ProtoIdl;
import io.edap.protobuf.idl.ServiceParser;
import io.edap.protobuf.wire.Proto;

import java.util.Map;

/**
 * 运行期基于反射机制反射符合条件的Class对象来解析生成proto描述的服务的对象
 */
public class ReflectServiceParser implements ServiceParser {
    @Override
    public ProtoIdl parseServices(BuildOption buildeOption) {
        return null;
    }

    @Override
    public void buildBeanProto(String clazzName, String serviceName, BuildOption buildOption, ProtoIdl protoIdl) {

    }
}
