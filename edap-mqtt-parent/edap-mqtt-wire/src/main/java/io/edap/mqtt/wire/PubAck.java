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

import java.util.Map;

public class PubAck extends ControlPacket {

    private int packetIdentifier;

    private int reasonCode;
    private String reason;
    private Map<String, String> userProperty;

    public PubAck(int fixedHeaderByte) {
        super(ControlPacketType.PUBACK, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public int getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(int reasonCode) {
        this.reasonCode = reasonCode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, String> getUserProperty() {
        return userProperty;
    }

    public void setUserProperty(Map<String, String> userProperty) {
        this.userProperty = userProperty;
    }
}
