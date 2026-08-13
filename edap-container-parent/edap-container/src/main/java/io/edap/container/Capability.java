package io.edap.container;

import java.util.EnumSet;
import java.util.Set;

/**
 * 容器节点的能力——决定 {@link Container} 启动期 bind 哪些 Router。
 *
 * <p>与"协议"的区别：
 * <ul>
 *   <li><b>协议</b>（HTTP / WS / eRPC / gRPC）是应用侧视角："我这个 service 用了哪个协议发布"</li>
 *   <li><b>能力</b>（本枚举）是容器侧视角："我这个节点能不能处理这种协议请求"</li>
 * </ul></p>
 *
 * <p><b>为什么 {@link #HTTP} 和 {@link #WS} 是两个独立能力</b>：虽然 HTTP 和
 * WebSocket 物理上同端口（HTTP Upgrade 握手），但握手后的<b>消息处理模型完全不同</b>：
 * HTTP 是请求-响应单向模型（客户端发起，服务器不能主动 push），WS 是双向长连接（服务器可主动
 * 推送）。绑成同一个 capability 会让 bind 阶段把 WS 路由强行塞进 HTTP Router 的 path 列表，
 * 违反多协议嗅探设计（README §8.1）。</p>
 *
 * <p><b>使用方式</b>：</p>
 * <pre>{@code
 * // 方式 1：编程式指定（推荐用于测试 / 单节点部署脚本）
 * Container c = new Container(appsDir,
 *         EnumSet.of(Capability.HTTP, Capability.WS));
 *
 * // 方式 2：默认构造从系统属性 edap.node.capabilities 解析
 * //   edap.node.capabilities=http,ws        → HTTP + WS
 * //   edap.node.capabilities=erpc           → ERPC
 * //   edap.node.capabilities=http,ws,erpc   → 混合节点（HTTP+WS+eRPC）
 * Container c = new Container(appsDir);
 * }</pre>
 */
public enum Capability {
    /**
     * HTTP 请求-响应路由（GET/POST/PUT/DELETE + body 解析）
     */
    HTTP,
    /**
     * WebSocket 双向长连接路由（Upgrade 握手 + 消息帧分发）
     */
    WS,
    /**
     * eRPC 二进制帧路由（微服务内部通信）
     */
    ERPC,
    /**
     * gRPC 帧路由（protobuf 序列化，对外互通）
     */
    GRPC;

    /**
     * 把 "http,ws,erpc" 这种逗号分隔字符串解析成 EnumSet。
     * token 大小写不敏感，未识别 token 跳过（静默——典型场景是配置项有错，调用方启动期就知道）。
     */
    public static Set<Capability> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(Capability.class);
        }
        Set<Capability> set = EnumSet.noneOf(Capability.class);
        for (String tok : csv.split(",")) {
            String name = tok.trim();
            if (name.isEmpty()) continue;
            try {
                set.add(Capability.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // skip unknown token
            }
        }
        return set;
    }
}