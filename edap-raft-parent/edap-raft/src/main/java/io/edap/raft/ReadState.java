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

package io.edap.raft;

/**
 * 只读请求的确认状态，等价于 etcd 的 {@code ReadState}。
 *
 * <p>调用方必须先发起 ReadIndex 才能拿到这个状态，并且要靠
 * {@link #getRequestCtx()} 里的上下文区分哪个请求对应哪个状态。</p>
 */
public class ReadState {

    /** 可以安全读取的位置 */
    private final long   index;

    /** 请求方带回来的上下文，原样回传 */
    private final byte[] requestCtx;

    public ReadState(long index, byte[] requestCtx) {
        this.index = index;
        this.requestCtx = requestCtx == null ? new byte[0] : requestCtx;
    }

    /**
     * 可以安全读取的位置
     */
    public long getIndex() {
        return index;
    }

    /**
     * 请求方带回来的上下文
     */
    public byte[] getRequestCtx() {
        return requestCtx;
    }

    /**
     * 与 etcd 一致的可读描述。
     *
     * <p>etcd 用 {@code %v} 打印 {@code pb.ReadState} 得到的是默认的结构体格式，
     * 即 {@code {1 [1 2 3]}}，这里保持一致。</p>
     */
    @Override
    public String toString() {
        return "{" + index + " " + GoFormat.goBytes(requestCtx) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ReadState)) {
            return false;
        }
        ReadState other = (ReadState) o;
        return index == other.index && java.util.Arrays.equals(requestCtx, other.requestCtx);
    }

    @Override
    public int hashCode() {
        return (int) (index * 31 + java.util.Arrays.hashCode(requestCtx));
    }
}
