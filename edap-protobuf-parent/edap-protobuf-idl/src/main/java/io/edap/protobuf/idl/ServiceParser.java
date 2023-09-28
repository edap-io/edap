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

package io.edap.protobuf.idl;

import io.edap.protobuf.wire.Proto;

import java.util.Map;

/**
 * 定义服务扫描的解析器
 */
public interface ServiceParser {

    /**
     * 根据构建Proto描述文件的选项解析资源生成服务的Proto定义的对象Map，键为服务名称，值为Proto文件的对象
     * @param buildeOption
     * @return
     */
    ProtoIdl parseServices(BuildOption buildeOption);

    /**
     * 根据指定的javabean的类名构建该类名对应的Message，并将添加到ProtoIdl的dto对象中
     * @param clazzName 指定的class名称
     * @param protoIdl 该类所在ProtoIdl的对象
     */
    void buildBeanProto(String clazzName, String serviceName, BuildOption buildOption, ProtoIdl protoIdl);
}
