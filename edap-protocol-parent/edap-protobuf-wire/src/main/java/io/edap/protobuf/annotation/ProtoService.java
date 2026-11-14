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

package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 由proto文件定义的微服务注解
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Inherited
@Documented
public @interface ProtoService {

    /**
     * 生成该java文件的proto文件名称，通常在api包的resources/proto目录中
     * @return
     */
    String protoFile();

    /**
     * proto 中定义的服务的全名，形如 {@code pkg.SvcName}（proto package + "." + service 名）。
     *
     * <p><b>为什么需要</b>：gRPC 的 {@code :path} 为 {@code /<serviceName>/<Method>}，
     * 而 {@code serviceName} 必须取自 proto 定义——proto 的 {@code java_package} option
     * 可以把 Java 包名与 proto package 解耦，<b>不能</b>从 Java 包名反推。
     *
     * <p>为空串表示该服务不经 gRPC 暴露。
     *
     * @return proto 服务全名
     */
    String serviceName() default "";
}
