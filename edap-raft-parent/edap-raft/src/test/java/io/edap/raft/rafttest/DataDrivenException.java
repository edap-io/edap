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

package io.edap.raft.rafttest;

/**
 * 数据驱动测试里的用法错误。
 *
 * <p>etcd 用 {@code t.Fatal} / {@code t.Fatalf} 报告这类错误，
 * 也就是直接让用例失败。Java 侧用异常表达同样的意思：
 * 抛出本异常表示「这条命令写错了」，而不是 raft 本身出了问题。</p>
 */
public class DataDrivenException extends RuntimeException {

    public DataDrivenException(String message) {
        super(message);
    }

    public DataDrivenException(String message, Throwable cause) {
        super(message, cause);
    }
}
