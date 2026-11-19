/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.grpc.benchmark.thrift;

import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.TProcessor;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TMessage;
import org.apache.thrift.protocol.TMessageType;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;

import java.util.HashMap;
import java.util.Map;

/**
 * Thrift 侧的服务定义 —— <b>手写</b>的「thrift 编译器产物形态」代码。
 *
 * <p>等价的 thrift IDL：</p>
 * <pre>{@code
 * namespace java io.edap.grpc.benchmark.thrift
 *
 * struct ThriftPayload {
 *   1: string name
 *   2: i32    value
 *   3: binary blob
 * }
 *
 * service ThriftBenchService {
 *   ThriftPayload unary(1: ThriftPayload request)
 * }
 * }</pre>
 *
 * <p>★ <b>thrift 的调用形态与 gRPC 差别很大</b>，这决定了后面所有的对比口径：</p>
 * <ul>
 *   <li>thrift <b>没有「请求 id」这个概念</b> —— 一元调用靠「方法名 + 消息类型 + seqid」
 *       自描述，但服务端<b>不会</b>像 gRPC 那样在一条连接上并行处理多个请求；</li>
 *   <li>thrift 的服务端实现（TThreadPoolServer / TThreadedSelectorServer）
 *       <b>每条连接同一时刻只有 1 个在途请求</b>
 *       （源码依据见报告「口径与已知差异」一节）；</li>
 *   <li>⇒ N 路并发在途请求 ⇒ Thrift 侧必须用 <b>N 条连接</b>。</li>
 * </ul>
 */
public final class ThriftBenchService {

    /** 方法名 —— 与 gRPC 的 {@code Unary} 对应。 */
    public static final String METHOD_UNARY = "unary";

    /**
     * 结果结构体的名字 —— 与 thrift 生成代码一致（{@code ThriftBenchService_unary_result}）。
     *
     * <p>thrift 的结果结构体是<b>协议的一部分</b>（不是日志用的名字），但保持一致
     * 才能让「别的语言生成的 thrift 客户端」直接连上来。</p>
     */
    public static final String RESULT_STRUCT_NAME = "ThriftBenchService_unary_result";

    private ThriftBenchService() {
    }

    /**
     * 服务接口 —— 服务端实现它，客户端通过 {@link Client} 调用。
     */
    public interface Iface {
        /**
         * 一元调用。
         *
         * @param request 请求消息
         * @return 响应消息
         */
        ThriftPayload unary(ThriftPayload request) throws TException;
    }

    /**
     * 服务端处理器 —— 实现 {@link TProcessor}，由 thrift 的服务端驱动调用。
     *
     * <p>★ <b>无状态</b>：可以在多条连接之间共享（thrift 默认每条连接建一个 processor，
     * 但这里业务是无状态的，共享一个更接近 gRPC 的模型，也少一次分配）。</p>
     */
    public static final class Processor implements TProcessor {

        private final Iface iface;

        /** 方法名 → 处理函数。 */
        private final Map<String, ProcessFunction> processMap = new HashMap<>();

        public Processor(Iface iface) {
            this.iface = iface;
            processMap.put(METHOD_UNARY, new Unary());
        }

        @Override
        public void process(TProtocol in, TProtocol out) throws TException {
            TMessage msg = in.readMessageBegin();
            ProcessFunction fn = processMap.get(msg.name);
            if (fn == null) {
                // 未知方法：按 thrift 的约定跳过参数、回一个 UNKNOWN_METHOD 异常
                TProtocolUtil.skip(in, TType.STRUCT);
                in.readMessageEnd();
                TApplicationException x = new TApplicationException(
                    TApplicationException.UNKNOWN_METHOD, "Invalid method name: '" + msg.name + "'");
                out.writeMessageBegin(new TMessage(msg.name, TMessageType.EXCEPTION, msg.seqid));
                x.write(out);
                out.writeMessageEnd();
                out.getTransport().flush();
                return;
            }
            fn.process(in, out, msg.seqid);
        }

        /**
         * 一个方法的处理函数。
         */
        private interface ProcessFunction {
            void process(TProtocol in, TProtocol out, int seqid) throws TException;
        }

        /**
         * {@code unary} 的处理函数。
         */
        private final class Unary implements ProcessFunction {
            @Override
            public void process(TProtocol in, TProtocol out, int seqid) throws TException {
                ThriftPayload request = new ThriftPayload();
                request.read(in);

                ThriftPayload result;
                try {
                    result = iface.unary(request);
                } catch (Throwable t) {
                    // 业务异常 ⇒ 按 thrift 的约定回一个 INTERNAL_ERROR 异常消息
                    TApplicationException x = new TApplicationException(
                        TApplicationException.INTERNAL_ERROR, String.valueOf(t.getMessage()));
                    out.writeMessageBegin(new TMessage(METHOD_UNARY, TMessageType.EXCEPTION, seqid));
                    x.write(out);
                    out.writeMessageEnd();
                    out.getTransport().flush();
                    return;
                }

                out.writeMessageBegin(new TMessage(METHOD_UNARY, TMessageType.REPLY, seqid));
                // ★ 结果要包在「结果结构体」里（thrift 生成代码的形态）：
                //   struct ThriftBenchService_unary_result { 0: ThriftPayload success }
                //   少这一层 ⇒ 用别的语言生成的 thrift 客户端解不出来。
                out.writeStructBegin(new TStruct(RESULT_STRUCT_NAME));
                out.writeFieldBegin(new TField("success", TType.STRUCT, (short) 0));
                result.write(out);
                out.writeFieldEnd();
                out.writeFieldStop();
                out.writeStructEnd();
                out.writeMessageEnd();
                out.getTransport().flush();
            }
        }
    }

    /**
     * 客户端 —— 与 thrift 生成代码里的 {@code Client} 形态一致：拆成 send / recv 两半。
     *
     * <p>★ <b>一条连接一个实例</b>：thrift 的客户端是「同步阻塞」的，
     * 连接内同一时刻只能有一个在途调用 ⇒ 连接池由 {@code ThriftBenchClient} 管，
     * 每次调用前借一条、调用后归还。</p>
     */
    public static final class Client implements Iface {

        private final TProtocol iprot;
        private final TProtocol oprot;

        /** 消息序号（thrift 自描述消息头的一部分）。 */
        private int seqid;

        public Client(TProtocol iprot, TProtocol oprot) {
            this.iprot = iprot;
            this.oprot = oprot;
        }

        @Override
        public ThriftPayload unary(ThriftPayload request) throws TException {
            send_unary(request);
            return recv_unary();
        }

        /**
         * 发出请求（不读响应）。
         */
        public void send_unary(ThriftPayload request) throws TException {
            oprot.writeMessageBegin(new TMessage(METHOD_UNARY, TMessageType.CALL, ++seqid));
            request.write(oprot);
            oprot.writeMessageEnd();
            oprot.getTransport().flush();
        }

        /**
         * 读响应。
         */
        public ThriftPayload recv_unary() throws TException {
            TMessage msg = iprot.readMessageBegin();
            if (msg.type == TMessageType.EXCEPTION) {
                TApplicationException x = TApplicationException.readFrom(iprot);
                iprot.readMessageEnd();
                throw x;
            }
            ThriftPayload result = new ThriftPayload();
            iprot.readStructBegin();
            while (true) {
                TField field = iprot.readFieldBegin();
                if (field.type == TType.STOP) {
                    break;
                }
                if (field.id == 0 && field.type == TType.STRUCT) {
                    result.read(iprot);
                } else {
                    TProtocolUtil.skip(iprot, field.type);
                }
                iprot.readFieldEnd();
            }
            iprot.readStructEnd();
            iprot.readMessageEnd();
            return result;
        }
    }
}
