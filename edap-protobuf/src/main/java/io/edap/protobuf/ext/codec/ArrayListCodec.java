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

import java.util.ArrayList;

import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAYLIST_END;
import static io.edap.protobuf.ext.AnyCodec.RANGE_ARRAYLIST_START;

/**
 * ArrayList的编解码器
 */
public class ArrayListCodec implements ExtCodec<ArrayList> {

    private ArrayList EMPTY_LIST = new ArrayList();

    private Integer size;

    public ArrayListCodec() {

    }

    public ArrayListCodec(Integer size) {
        this.size = size;
    }

    @Override
    public ArrayList decode(ProtoBufReader reader) throws ProtoBufException {
        int len;
        if (null != this.size) {
            len = size.intValue();
        } else {
            len = reader.readInt32();
        }
        if (len == 0) {
            return EMPTY_LIST;
        }
        ArrayList list = new ArrayList(len);
        for (int i=0;i<len;i++) {
            list.add(reader.readObject());
        }
        return list;
    }

    @Override
    public void encode(ProtoBufWriter writer, ArrayList arrayList) throws EncodeException {
        int len = arrayList.size();
        if (writer.getWriteOrder() == ProtoBufWriter.WriteOrder.SEQUENTIAL) {
            if (len > RANGE_ARRAYLIST_END - RANGE_ARRAYLIST_START) {
                writer.writeByte((byte)RANGE_ARRAYLIST_END);
                writer.writeInt32(len, true);
            } else {
                writer.writeInt32(RANGE_ARRAYLIST_START + len);
            }
            for (int i=0;i<len;i++) {
                writer.writeObject(arrayList.get(i));
            }
        } else {
            for (int i=len-1;i>=0;i--) {
                writer.writeObject(arrayList.get(i));
            }
            if (len > RANGE_ARRAYLIST_END - RANGE_ARRAYLIST_START) {
                writer.writeInt32(len, true);
                writer.writeByte((byte)RANGE_ARRAYLIST_END);
            } else {
                writer.writeByte((byte)(RANGE_ARRAYLIST_START + len));
            }

        }
    }
}
