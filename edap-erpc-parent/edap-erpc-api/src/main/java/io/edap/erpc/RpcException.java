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

package io.edap.erpc;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;

import java.util.List;

public class RpcException {

    /**
     * 异常的类名
     */
    @ProtoField(tag = 1, type = Field.Type.STRING)
    private String clazzName;
    /**
     * 异常描述信息
     */
    @ProtoField(tag = 2, type = Field.Type.STRING)
    private String message;
    /**
     * 异常的堆栈信息
     */
    @ProtoField(tag = 3, type = Field.Type.MESSAGE, cardinality = Field.Cardinality.REPEATED)
    private List<StackTraceElement> stackElement;
    /**
     * eRPC 状态码（{@link ErpcStatus#code()}）；0 表示没有状态信息。
     *
     * <p>ERROR 帧的载荷就是本类 ⇒ 客户端靠这个字段知道是「方法不存在」还是「业务抛异常」。</p>
     */
    @ProtoField(tag = 4, type = Field.Type.INT32)
    private int status;

    /**
     * 异常的类名
     */
    public String getClazzName() {
        return clazzName;
    }

    public void setClazzName(String clazzName) {
        this.clazzName = clazzName;
    }

    /**
     * 异常描述信息
     */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 异常的堆栈信息
     */
    public List<StackTraceElement> getStackElement() {
        return stackElement;
    }

    public void setStackElement(List<StackTraceElement> stackElement) {
        this.stackElement = stackElement;
    }

    /**
     * eRPC 状态码（{@link ErpcStatus#code()}）
     */
    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    /**
     * 状态码对应的状态枚举。
     */
    public ErpcStatus getStatusEnum() {
        return ErpcStatus.fromCode(status);
    }

    public static class StackTraceElement {
        /**
         * 抛出异常的类名
         */
        @ProtoField(tag = 1, type = Field.Type.STRING)
        private String declaringClass;
        /**
         * 抛出异常的方法名
         */
        @ProtoField(tag = 2, type = Field.Type.STRING)
        private String methodName;
        /**
         * 抛出异常的类的文件名
         */
        @ProtoField(tag = 3, type = Field.Type.STRING)
        private String fileName;
        /**
         * 抛异常的行号
         */
        @ProtoField(tag = 4, type = Field.Type.INT32)
        private int    lineNumber;

        /**
         * 抛出异常的类名
         */
        public String getDeclaringClass() {
            return declaringClass;
        }

        public void setDeclaringClass(String declaringClass) {
            this.declaringClass = declaringClass;
        }

        /**
         * 抛出异常的方法名
         */
        public String getMethodName() {
            return methodName;
        }

        public void setMethodName(String methodName) {
            this.methodName = methodName;
        }

        /**
         * 抛出异常的类的文件名
         */
        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        /**
         * 抛异常的行号
         */
        public int getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(int lineNumber) {
            this.lineNumber = lineNumber;
        }
    }
}
