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

package io.edap.raft.cluster;

/**
 * 客户端提案的请求体（走 HTTP 时用）。
 *
 * <p><b>载荷用 base64</b>：JSON 里没有二进制，byte[] 直接塞进去会被转成
 * 数字数组，体积暴涨 3~4 倍。base64 只涨 33%。</p>
 *
 * <p><b>★ 体积上限</b>：edap 的 HttpServer 在 NORMAL decoder 下
 * 超过约 15KB 的请求体会挂住，所以单个提案的载荷建议控制在 10KB 以内。</p>
 */
public class RaftProposalRequest {

    /**
     * 请求 id：幂等键。重试时用<b>同一个</b> id，
     * 主按它去重 ⇒ 业务状态机只应用一次。
     */
    private long   requestId;

    /** base64 编码的载荷 */
    private String payloadBase64;

    public RaftProposalRequest() {
    }

    public RaftProposalRequest(long requestId, String payloadBase64) {
        this.requestId = requestId;
        this.payloadBase64 = payloadBase64;
    }

    public long getRequestId() {
        return requestId;
    }

    public void setRequestId(long requestId) {
        this.requestId = requestId;
    }

    public String getPayloadBase64() {
        return payloadBase64;
    }

    public void setPayloadBase64(String payloadBase64) {
        this.payloadBase64 = payloadBase64;
    }

    @Override
    public String toString() {
        return "RaftProposalRequest{requestId=" + requestId + "}";
    }
}
