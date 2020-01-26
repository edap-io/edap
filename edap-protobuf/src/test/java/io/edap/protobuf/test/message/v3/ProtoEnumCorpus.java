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

package io.edap.protobuf.test.message.v3;

import io.edap.protobuf.ProtoBufEnum;

public enum ProtoEnumCorpus implements ProtoBufEnum {
    UNIVERSAL(0),
    WEB(1),
    IMAGES(2),
    LOCAL(3),
    NEWS(4),
    PRODUCTS(5),
    VIDEO(6);

    private int value;

    private ProtoEnumCorpus(int value) {
        this.value = value;
    }

    @Override
    public int getValue() {
        return this.value;
    }

    public static ProtoEnumCorpus valueOf(int value) {
        switch (value) {
            case 0:
                return UNIVERSAL;
            case 1:
                return WEB;
            case 2:
                return IMAGES;
            case 3:
                return LOCAL;
            case 4:
                return NEWS;
            case 5:
                return PRODUCTS;
            case 6:
                return VIDEO;
            default:

        }
        return null;
    }
}
