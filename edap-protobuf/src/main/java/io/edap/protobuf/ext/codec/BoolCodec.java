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

package io.edap.protobuf.ext.codec;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.ExtCodec;

import static io.edap.protobuf.ext.AnyCodec.RANGE_BOOL_FALSE;
import static io.edap.protobuf.ext.AnyCodec.RANGE_BOOL_TRUE;

/**
 * 布尔类型的编解码器
 */
public class BoolCodec implements ExtCodec<Boolean> {

    private Boolean val;

    public BoolCodec() {

    }

    public BoolCodec(Boolean val) {
        this.val = val;
    }

    @Override
    public Boolean decode(ProtoBufReader reader) throws ProtoBufException {
        return val;
    }

    @Override
    public void encode(ProtoBufWriter writer, Boolean v) throws EncodeException {
        if (null == v || !v) {
            writer.writeByte((byte)RANGE_BOOL_FALSE);
        } else {
            writer.writeByte((byte)RANGE_BOOL_TRUE);
        }
    }
}