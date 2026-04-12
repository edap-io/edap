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

public abstract class ControlPacket {

    private ControlPacketType type;

    private int dup;

    private QoSLevel qos;

    private int retain;

    private Integer lowFourBits;

    public ControlPacket(ControlPacketType type, int fixedHeaderByte) {
        this.type = type;
        parseLowFourBits(fixedHeaderByte);
    }

    private void parseLowFourBits(int fixedHeaderByte) {
        int value = fixedHeaderByte & 0x0F;
        this.lowFourBits = value;
        retain = value & 0x1;
        qos    = QoSLevel.fromValue((value >> 1) & 0x3);
        dup    = value >> 3;
    }

    public int getLowFourBits() {
        if (lowFourBits == null) {
            int lowFour = retain & 0x1;
            lowFour |= (qos.getValue() & 0x3) << 1;
            lowFour |= (dup & 0x1) << 3;
            lowFourBits = lowFour;
        }
        return this.lowFourBits;
    }

    public ControlPacketType getType() {
        return type;
    }

    public int getDup() {
        return dup;
    }

    public void setDup(int dup) {
        if (this.dup == dup) {
            return;
        }
        lowFourBits = null;
        this.dup = dup;
    }

    public QoSLevel getQos() {
        return qos;
    }

    public void setQos(QoSLevel qos) {
        if (this.qos == qos) {
            return;
        }
        lowFourBits = null;
        this.qos = qos;
    }

    public int getRetain() {
        return retain;
    }

    public void setRetain(int retain) {
        if (this.retain == retain) {
            return;
        }
        lowFourBits = null;
        this.retain = retain;
    }
}
