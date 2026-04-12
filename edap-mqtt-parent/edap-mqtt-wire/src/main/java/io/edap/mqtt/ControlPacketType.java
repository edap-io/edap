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

package io.edap.mqtt;

public enum ControlPacketType {

    RESERVED   ( 0),
    CONNECT    ( 1),
    CONNACK    ( 2),
    PUBLISH    ( 3),
    PUBACK     ( 4),
    PUBREC     ( 5),
    PUBREL     ( 6),
    PUBCOMP    ( 7),
    SUBSCRIBE  ( 8),
    SUBACK     ( 9),
    UNSUBSCRIBE(10),
    UNSUBACK   (11),
    PINGREQ    (12),
    PINGRESP   (13),
    DISCONNECT (14),
    AUTH       (15);

    public static final int RESERVED_VALUE    =  0;
    public static final int CONNECT_VALUE     =  1;
    public static final int CONNACK_VALUE     =  2;
    public static final int PUBLISH_VALUE     =  3;
    public static final int PUBACK_VALUE      =  4;
    public static final int PUBREC_VALUE      =  5;
    public static final int PUBREL_VALUE      =  6;
    public static final int PUBCOMP_VALUE     =  7;
    public static final int SUBSCRIBE_VALUE   =  8;
    public static final int SUBACK_VALUE      =  9;
    public static final int UNSUBSCRIBE_VALUE = 10;
    public static final int UNSUBACK_VALUE    = 11;
    public static final int PINGREQ_VALUE     = 12;
    public static final int PINGRESP_VALUE    = 13;
    public static final int DISCONNECT_VALUE  = 14;
    public static final int AUTH_VALUE        = 15;

    private int value;

    static ControlPacketType[] TYPES = new ControlPacketType[]{
            RESERVED,
            CONNECT,
            CONNACK,
            PUBLISH,
            PUBACK,
            PUBREC,
            PUBREL,
            PUBCOMP,
            SUBSCRIBE,
            SUBACK,
            UNSUBSCRIBE,
            UNSUBACK,
            PINGREQ,
            PINGRESP,
            DISCONNECT,
            AUTH
    };

    ControlPacketType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static ControlPacketType fromValue(int value) {
        if (value >= 0 && value < 16) {
            return TYPES[value];
        }

        return RESERVED;
    }
}
