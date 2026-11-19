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

import org.apache.thrift.TException;
import org.apache.thrift.TSerializable;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Thrift 侧的请求消息 —— <b>手写</b>的「thrift 编译器产物形态」代码。
 *
 * <p>★ <b>为什么手写</b>：环境里没有 thrift 编译器，而本基准需要一个与
 * {@link io.edap.grpc.benchmark.Payload}（protobuf：name=1/string、value=2/int32、blob=3/bytes）
 * <b>逐字段等价</b>的消息。字段编号、字段类型、字段内容都一样 ⇒ 业务语义一致，
 * 才谈得上「同一个业务、不同的传输框架」的对比。</p>
 *
 * <p><b>wire 形态</b>（{@code TBinaryProtocol}）：
 * {@code [message header][struct]=[field header][value]...STOP}，
 * field header = 1 字节 type + 2 字节 field id。</p>
 *
 * <p><b>thrift 与 protobuf 的结构性差异</b>（会体现在 micro 对比里）：</p>
 * <ul>
 *   <li>thrift 的每个字段都带 <b>1 字节 type + 2 字节 id</b> 的字段头 ⇒ 固定开销比 protobuf 大；</li>
 *   <li>thrift <b>没有</b> protobuf 那样的「未知字段跳过」语义，但有 {@code TProtocolUtil.skip}
 *       做向前兼容 —— 本类照抄了生成代码的 skip 逻辑；</li>
 *   <li>string 走 {@code TType.STRING}，binary 也走 {@code TType.STRING}（类型标记相同，
 *       区别只在语义）；</li>
 *   <li>thrift 的消息是<b>自描述</b>的（方法名 + 消息类型 + seqid）⇒ 每帧多几个字节。</li>
 * </ul>
 */
public class ThriftPayload implements TSerializable, Comparable<ThriftPayload>, Serializable {

    private static final long serialVersionUID = 1L;

    /** 字段 1：name（string）。 */
    private String name;

    /** 字段 2：value（i32）。 */
    private int value;

    /** 字段 3：blob（binary）。 */
    private byte[] blob;

    public ThriftPayload() {
    }

    public ThriftPayload(String name, int value, byte[] blob) {
        this.name = name;
        this.value = value;
        this.blob = blob;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    public byte[] getBlob() {
        return blob;
    }

    public void setBlob(byte[] blob) {
        this.blob = blob;
    }

    // ------------------------------------------------------------------
    // thrift 生成代码形态：read / write
    // ------------------------------------------------------------------

    /**
     * 从协议里读出一个结构体 —— 与 thrift 生成代码逐行同构。
     */
    public void read(TProtocol iprot) throws TException {
        iprot.readStructBegin();
        while (true) {
            TField field = iprot.readFieldBegin();
            if (field.type == TType.STOP) {
                break;
            }
            switch (field.id) {
                case 1:
                    if (field.type == TType.STRING) {
                        this.name = iprot.readString();
                    } else {
                        TProtocolUtil.skip(iprot, field.type);
                    }
                    break;
                case 2:
                    if (field.type == TType.I32) {
                        this.value = iprot.readI32();
                    } else {
                        TProtocolUtil.skip(iprot, field.type);
                    }
                    break;
                case 3:
                    if (field.type == TType.STRING) {
                        ByteBuffer buffer = iprot.readBinary();
                        this.blob = new byte[buffer.remaining()];
                        buffer.get(this.blob);
                    } else {
                        TProtocolUtil.skip(iprot, field.type);
                    }
                    break;
                default:
                    TProtocolUtil.skip(iprot, field.type);
                    break;
            }
            iprot.readFieldEnd();
        }
        iprot.readStructEnd();
    }

    /**
     * 把结构体写进协议 —— 与 thrift 生成代码逐行同构。
     */
    public void write(TProtocol oprot) throws TException {
        oprot.writeStructBegin(new TStruct("ThriftPayload"));
        oprot.writeFieldBegin(new TField("name", TType.STRING, (short) 1));
        oprot.writeString(this.name);
        oprot.writeFieldEnd();
        oprot.writeFieldBegin(new TField("value", TType.I32, (short) 2));
        oprot.writeI32(this.value);
        oprot.writeFieldEnd();
        oprot.writeFieldBegin(new TField("blob", TType.STRING, (short) 3));
        oprot.writeBinary(this.blob == null ? ByteBuffer.allocate(0) : ByteBuffer.wrap(this.blob));
        oprot.writeFieldEnd();
        oprot.writeFieldStop();
        oprot.writeStructEnd();
    }

    // ------------------------------------------------------------------
    // 生成代码里的标准件
    // ------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ThriftPayload)) {
            return false;
        }
        ThriftPayload that = (ThriftPayload) o;
        return this.value == that.value
            && (this.name == null ? that.name == null : this.name.equals(that.name))
            && Arrays.equals(this.blob, that.blob);
    }

    @Override
    public int hashCode() {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + value;
        result = 31 * result + Arrays.hashCode(blob);
        return result;
    }

    @Override
    public int compareTo(ThriftPayload other) {
        if (other == null) {
            throw new NullPointerException();
        }
        int c = compareStrings(this.name, other.name);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(this.value, other.value);
        if (c != 0) {
            return c;
        }
        return compareBytes(this.blob, other.blob);
    }

    private static int compareStrings(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : a.compareTo(b);
    }

    private static int compareBytes(byte[] a, byte[] b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int c = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (c != 0) {
                return c;
            }
        }
        return a.length - b.length;
    }

    @Override
    public String toString() {
        return "ThriftPayload(name=" + name + ", value=" + value
            + ", blob=" + (blob == null ? "null" : blob.length + " bytes") + ")";
    }
}
