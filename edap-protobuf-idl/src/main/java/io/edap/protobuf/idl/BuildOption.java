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

/**
 * 根据java的class生成proto服务描述文件的选项
 */
public class BuildOption {
    /**
     * 是否按grpc兼容模式输出
     */
    private boolean grpcCompatible;

    /**
     * 是否按grpc兼容模式输出
     * @return
     */
    public boolean isGrpcCompatible() {
        return grpcCompatible;
    }

    public void setGrpcCompatible(boolean grpcCompatible) {
        this.grpcCompatible = grpcCompatible;
    }
}
