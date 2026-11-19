/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc;

/**
 * {@link ErpcAuthenticator} 的返回结果。
 *
 * <p>成功 / 失败两种形态用同一个 POJO 承载 —— 鉴权失败是<b>预期分支</b>，
 * 不是异常 ⇒ 不用异常做控制流（与 {@code io.edap.http.ws.AuthResult} 同一套路）。</p>
 *
 * <p><b>字段语义</b>：</p>
 * <ul>
 *   <li>{@link #ok()}：校验是否通过；</li>
 *   <li>{@link #principal()}：通过时的<b>认证主体</b>（如用户名 / 租户号）——
 *       写进那条连接的 {@code RpcChannelContext}；失败时为 {@code null}；</li>
 *   <li>{@link #reason()}：失败原因（写进 ERROR 帧的消息里）；可为 {@code null}。</li>
 * </ul>
 */
public final class ErpcAuthResult {

    private final boolean ok;
    private final String principal;
    private final String reason;

    private ErpcAuthResult(boolean ok, String principal, String reason) {
        this.ok = ok;
        this.principal = principal;
        this.reason = reason;
    }

    /**
     * 校验通过。
     *
     * @param principal 认证主体（如用户名 / 租户号）；可为 {@code null}
     */
    public static ErpcAuthResult ok(String principal) {
        return new ErpcAuthResult(true, principal, null);
    }

    /**
     * 校验失败。
     *
     * @param reason 失败原因（写进 ERROR 帧的消息里）；可为 {@code null}
     */
    public static ErpcAuthResult fail(String reason) {
        return new ErpcAuthResult(false, null, reason);
    }

    public boolean ok() {
        return ok;
    }

    public String principal() {
        return principal;
    }

    public String reason() {
        return reason;
    }
}
