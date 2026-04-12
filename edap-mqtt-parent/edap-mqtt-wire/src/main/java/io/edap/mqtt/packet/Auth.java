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

import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;

import java.util.LinkedHashMap;

/**
 * @since mqtt-v5.0
 */
public class Auth extends ControlPacket {

    private int reasonCode;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;

    public Auth(int fixedHeaderByte) {
        super(ControlPacketType.AUTH, fixedHeaderByte);
    }

    public int getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(int reasonCode) {
        this.reasonCode = reasonCode;
    }

    /**
     * @since mqtt-v5.0
     */
    public LinkedHashMap<PropertyType, PacketProperty> getProperties() {
        return properties;
    }

    public void setProperties(LinkedHashMap<PropertyType, PacketProperty> properties) {
        this.properties = properties;
    }

}
