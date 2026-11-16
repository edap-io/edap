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
 * 客户端提案的应答。
 *
 * <ul>
 *   <li>{@code ok=true}：提案已提交（主已应用）；</li>
 *   <li>{@code ok=true, duplicate=true}：这个 requestId <b>已经应用过了</b> ——
 *       客户端重试时拿到的是这个结果，业务状态机没有重复应用；</li>
 *   <li>{@code ok=false}：失败，{@code message} 是原因。</li>
 * </ul>
 */
public class RaftProposalResult {

    private boolean ok;

    /** 是不是「重复的 requestId，已经应用过了」。 */
    private boolean duplicate;

    /** 失败原因。 */
    private String message;

    public RaftProposalResult() {
    }

    public static RaftProposalResult success(boolean duplicate) {
        RaftProposalResult r = new RaftProposalResult();
        r.ok = true;
        r.duplicate = duplicate;
        return r;
    }

    public static RaftProposalResult failure(String message) {
        RaftProposalResult r = new RaftProposalResult();
        r.ok = false;
        r.message = message;
        return r;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public void setDuplicate(boolean duplicate) {
        this.duplicate = duplicate;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public String toString() {
        return "RaftProposalResult{ok=" + ok + ", duplicate=" + duplicate
                + ", message=" + message + "}";
    }
}
