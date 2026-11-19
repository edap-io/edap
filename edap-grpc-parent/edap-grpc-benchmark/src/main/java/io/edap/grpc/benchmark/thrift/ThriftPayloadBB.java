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
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolUtil;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;

import java.nio.ByteBuffer;

/**
 * ★ 「blob 保持 {@link ByteBuffer}」的 thrift DTO 变体 ——
 * <b>thrift 0.14 生成代码的真实形态</b>。
 *
 * <p>thrift 0.14 的 {@code TProtocol.readBinary()} 返回 {@link ByteBuffer}
 * ⇒ 生成代码里 binary 字段就是 {@code ByteBuffer}，直接赋值 ⇒ <b>不多付那次
 * 「new byte[] + get()」</b>。</p>
 *
 * <p>本基准手写的 {@link ThriftPayload} 把字段声明成 {@code byte[]}，
 * 于是多了一次 {@code new byte[remaining]} + {@code buffer.get(...)}
 * —— 那是<b>手写代码引入的</b>，不是 thrift 协议的固有成本。</p>
 *
 * <p>★ 用它做解码 micro，才能量到「thrift 协议本身的解码成本」，
 * 而不是「本基准手写代码的成本」。</p>
 */
public class ThriftPayloadBB {

    /** 字段 1：name（string）。 */
    private String name;

    /** 字段 2：value（i32）。 */
    private int value;

    /** 字段 3：blob（binary）—— ★ 保持 ByteBuffer，不拷成 byte[]。 */
    private ByteBuffer blob;

    public ThriftPayloadBB() {
    }

    public ThriftPayloadBB(String name, int value, byte[] blob) {
        this.name = name;
        this.value = value;
        this.blob = blob == null ? null : ByteBuffer.wrap(blob);
    }

    public String getName() {
        return name;
    }

    public int getValue() {
        return value;
    }

    public ByteBuffer getBlob() {
        return blob;
    }

    /**
     * 从协议里读出一个结构体 —— 与 thrift 0.14 生成代码同构（blob 是 ByteBuffer）。
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
                        // ★ thrift 0.14 生成代码形态：直接赋值，不额外拷一次
                        this.blob = iprot.readBinary();
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
     * 把结构体写进协议 —— 与 thrift 生成代码同构。
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
        oprot.writeBinary(this.blob == null ? ByteBuffer.allocate(0) : this.blob);
        oprot.writeFieldEnd();
        oprot.writeFieldStop();
        oprot.writeStructEnd();
    }
}
