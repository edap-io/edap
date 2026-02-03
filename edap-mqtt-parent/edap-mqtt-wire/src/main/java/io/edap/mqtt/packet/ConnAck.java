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

package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;

import java.util.LinkedHashMap;
import java.util.List;

public class ConnAck extends ControlPacket {

    private int connAckFlag;
    private int connAckCode;
    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;

    public ConnAck(int fixedHeaderByte) {
        super(ControlPacketType.CONNACK, fixedHeaderByte);
    }

    public int getConnAckFlag() {
        return connAckFlag;
    }

    public void setConnAckFlag(int connAckFlag) {
        this.connAckFlag = connAckFlag;
    }

    public int getConnAckCode() {
        return connAckCode;
    }

    public void setConnAckCode(int connAckCode) {
        this.connAckCode = connAckCode;
    }

    public LinkedHashMap<PropertyType, PacketProperty> getProperties() {
        return properties;
    }

    public void setProperties(LinkedHashMap<PropertyType, PacketProperty> properties) {
        this.properties = properties;
    }
}
