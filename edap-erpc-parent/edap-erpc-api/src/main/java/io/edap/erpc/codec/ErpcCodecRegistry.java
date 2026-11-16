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

package io.edap.erpc.codec;

import io.edap.erpc.codec.json.JsonErpcCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编解码器注册表 —— eRPC 的「用哪种序列化框架」配置落在这里。
 *
 * <p>两把索引：</p>
 * <ul>
 *   <li><b>id → codec</b>：线上用。帧头的 {@code codec} 字节就是 id，
 *       收帧时按 id 找实现（{@link #byId(int)}）。</li>
 *   <li><b>name → codec</b>：配置用。{@code erpc.codec: json} 里的那个名字
 *       （{@link #byName(String)}，大小写不敏感）。</li>
 * </ul>
 *
 * <p><b>内置实现</b>：protobuf（默认，id=0）与 json（id=1）。
 * <b>扩展实现</b>：实现 {@link ErpcCodec} 并在
 * {@code META-INF/services/io.edap.erpc.codec.ErpcCodec} 登记 ⇒ 启动时自动装载；
 * 也可以 {@link #register(ErpcCodec)} 手工注册（同名/同 id 会覆盖）。</p>
 *
 * <p>★ 服务端**不需要**提前知道客户端用哪种序列化：帧头里带着 id，
 * 服务端收到哪种就用哪种解码，回帧时原样抄回（见
 * {@link io.edap.erpc.frame.ErpcFrameHeader}）。服务端只要求「认识客户端用的那个 id」，
 * 不认识 ⇒ 回 ERROR 帧（而不是断开连接）。</p>
 */
public final class ErpcCodecRegistry {

    private static final Logger LOG = LoggerManager.getLogger(ErpcCodecRegistry.class);

    private static final ErpcCodecRegistry INSTANCE = new ErpcCodecRegistry();

    static {
        INSTANCE.register(new ProtobufErpcCodec());
        INSTANCE.register(new JsonErpcCodec());
        // ServiceLoader 扩展：业务方自带的序列化实现
        try {
            for (ErpcCodec s : ServiceLoader.load(ErpcCodec.class)) {
                INSTANCE.register(s);
            }
        } catch (Throwable t) {
            // 扩展实现装载失败不影响内置实现 —— 内置的两个一定能用；
            // 但必须说清楚是谁、为什么失败（否则业务方只会看到「扩展没生效」而查不到原因）
            LOG.warn("erpc 扩展编解码器装载失败，内置实现仍可用: ", l -> l.threw(t));
        }
    }

    private final Map<Integer, ErpcCodec> byId   = new ConcurrentHashMap<>();
    private final Map<String, ErpcCodec>   byName = new ConcurrentHashMap<>();

    private ErpcCodecRegistry() {
    }

    public static ErpcCodecRegistry instance() {
        return INSTANCE;
    }

    /**
     * 注册一个编解码器。同 id / 同 name 会覆盖旧的。
     */
    public void register(ErpcCodec codec) {
        if (codec == null) {
            return;
        }
        byId.put(codec.id(), codec);
        String name = codec.name();
        if (name != null && !name.isEmpty()) {
            byName.put(name.toLowerCase(Locale.ENGLISH), codec);
        }
    }

    /**
     * 按线上协议 id 查找（收帧时用）。
     *
     * @return 找不到返回 {@code null}
     */
    public ErpcCodec byId(int id) {
        return byId.get(id);
    }

    /**
     * 按配置名查找（大小写不敏感）。
     *
     * @return 找不到返回 {@code null}
     */
    public ErpcCodec byName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return byName.get(name.toLowerCase(Locale.ENGLISH));
    }

    /**
     * 默认编解码器（protobuf）。找不到返回 {@code null} —— 正常情况下不会。
     */
    public ErpcCodec defaultCodec() {
        return byId.get(ErpcCodecIds.PROTOBUF);
    }

    /**
     * 已注册的全部实现（诊断用）。
     */
    public Collection<ErpcCodec> all() {
        return byId.values();
    }
}
