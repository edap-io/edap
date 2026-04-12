/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf.internal;

import io.edap.buffer.FastBuf;
import io.edap.io.BufOut;

/**
 * protobuf嵌套Message编码时使用的数据缓存对象
 */
public class ProtoBufOut implements BufOut {

    private final WriteBuf _writeBuf;

    public ProtoBufOut() {
        //_aioSession = aioSession;
        _writeBuf = new WriteBuf();
        _writeBuf.depthIndex = new boolean[128];
        _writeBuf.bs = new byte[8192];
        _writeBuf.len = _writeBuf.bs.length;
        _writeBuf.out = this;
    }

    @Override
    public void write(byte[] bs) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void write(byte[] bs, int offset, int len) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void write(byte b) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int remain() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int expand() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void reset() {
        _writeBuf.start    = 0;
//        _writeBuf.writeLen = 0;
//        _writeBuf.depth    = 1;
//        if (_writeBuf.bs.length > 8192) {
//            _writeBuf.bs = new byte[8192];
//            _writeBuf.len = _writeBuf.bs.length;
//        }

    }

    @Override
    public FastBuf getBuf() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public WriteBuf getWriteBuf() {
        return _writeBuf;
    }

    @Override
    public void setLocalBytes(byte[] bs) {
        _writeBuf.bs = bs;
        _writeBuf.len = bs.length;
    }

    @Override
    public boolean hasBuf() {
        return false;
    }
}