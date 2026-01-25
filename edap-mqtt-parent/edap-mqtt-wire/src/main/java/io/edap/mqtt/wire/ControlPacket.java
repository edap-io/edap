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

package io.edap.mqtt.wire;

public abstract class ControlPacket {

    private ControlPacketType type;

    private int dup;

    private QoSLevel qos;

    private int retain;

    public ControlPacket(ControlPacketType type, int fixedHeaderByte) {
        this.type = type;
        parseLowFourBits(fixedHeaderByte);
    }

    private void parseLowFourBits(int fixedHeaderByte) {
        int value = fixedHeaderByte & 0x0F;
        retain = value & 0b0001;
        qos    = QoSLevel.fromValue((value >> 1) & 0b011);
        dup    = value >> 3;
    }

    public ControlPacketType getType() {
        return type;
    }

    public int getDup() {
        return dup;
    }

    public QoSLevel getQos() {
        return qos;
    }

    public int getRetain() {
        return retain;
    }
}
