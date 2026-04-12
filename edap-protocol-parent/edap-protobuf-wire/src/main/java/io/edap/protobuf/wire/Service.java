/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf.wire;

import java.util.ArrayList;
import java.util.List;

/**
 * protocol buffer RPC服务数据结构定义
 */
public class Service {

    /**
     * 服务类型
     */
    public enum ServiceType {
        UNARY("unary"),
        SERVER_STREAM("server_stream"),
        CLIENT_STREAM("client_stream"),
        BIDIRECTIONAL("Bidirectional")
        ;

        private final String value;

        private ServiceType(String value) {
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }
    }

    /**
     * 服务的名称
     */
    private String name;
    /**
     * 服务定义的Method列表
     */
    private List<ServiceMethod> methods;
    /**
     * 服务块前的单行注释列表
     */
    private Comment comment;

    public Service setName(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return this.name;
    }

    public Service setComment(Comment comment) {
        this.comment = comment;
        return this;
    }

    public Comment getComments() {
        return comment;
    }

    public Service setMethods(List<ServiceMethod> methods) {
        if (methods instanceof ArrayList) {
            this.methods = methods;
        } else {
            getMethods();
            this.methods.addAll(methods);
        }
        return this;
    }

    public List<ServiceMethod> getMethods() {
        if (methods == null) {
            methods = new ArrayList<>();
        }
        return methods;
    }

    public Service addMethod(ServiceMethod parseMethod) {
        getMethods();
        methods.add(parseMethod);
        return this;
    }
}