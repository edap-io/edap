/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.nio;

import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.pool.Pool;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

/**
 * NIO 会话的统一关闭路径。
 *
 * <p>关一条连接要同时做三件事：<b>取消 selector key</b>、<b>关 channel</b>、
 * <b>把会话归还对象池</b>。三件事必须只做一遍 —— 会话是池化对象，归还两次就会
 * 让同一个会话实例同时挂在两条连接上（后一条连接关闭时会把前一条的 channel 关掉）。</p>
 *
 * <p>归还的闸门是 {@link NioServerSession#claimClose(java.nio.channels.SocketChannel)}：
 * 抢到闸门（CAS 把 channel 置空）的那一方才负责归还。空闲回收线程与 IO 线程
 * 同时关同一条连接时，只有一方能归还；会话若已被复用给新连接，旧连接的迟到关闭
 * 也抢不到闸门。</p>
 */
public final class NioSessionCloser {

    private static final Logger LOG = LoggerManager.getLogger(NioSessionCloser.class);

    private NioSessionCloser() {
    }

    /**
     * 关闭一条连接。
     *
     * @param server  所属 server（决定要不要把会话归还对象池），可为 null
     * @param key     该连接在 selector 上的 key（用来取消注册），可为 null
     * @param session 会话，可为 null
     */
    public static void close(Server server, SelectionKey key, NioServerSession session) {
        SocketChannel channel = resolveChannel(key, session);
        if (channel == null) {
            return;
        }
        try {
            if (key != null) {
                key.cancel();
            }
            SocketAddress remoteAddr = remoteAddress(channel);
            channel.close();
            if (remoteAddr != null) {
                LOG.info("channel {} closed", l -> l.arg(remoteAddr));
            } else {
                LOG.info("channel {} closed", l -> l.arg(channel));
            }
        } catch (IOException e) {
            LOG.warn("channel {} close error", l -> l.arg(channel).threw(e));
        } finally {
            requite(server, session, channel);
        }
    }

    private static SocketAddress remoteAddress(SocketChannel channel) {
        try {
            return channel.getRemoteAddress();
        } catch (IOException ignore) {
            // channel 可能已被对端关闭 —— 取不到远端地址不影响关闭本身
            return null;
        }
    }

    private static SocketChannel resolveChannel(SelectionKey key, NioServerSession session) {
        if (key != null) {
            Channel ch = key.channel();
            if (ch instanceof SocketChannel) {
                return (SocketChannel) ch;
            }
        }
        if (session != null) {
            return session.getSocketChannel();
        }
        return null;
    }

    /**
     * 把会话归还对象池 —— 只有抢到关闭闸门的那一方才能归还。
     */
    private static void requite(Server server, NioServerSession session, SocketChannel channel) {
        if (server == null || session == null || !server.isNioSesionPooled()) {
            return;
        }
        Pool<NioServerSession> pool = server.getNioSessionPool();
        if (pool == null) {
            return;
        }
        if (!session.claimClose(channel)) {
            // 这条连接已经被别人关过，或会话已被复用给别的连接 —— 不能二次归还
            return;
        }
        try {
            pool.requite(session);
        } catch (Throwable t) {
            LOG.warn("requite nio session failed", l -> l.arg(session.getClass().getName()).threw(t));
        }
    }
}
