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
import io.edap.mqtt.ProtocolLevel;

import java.util.LinkedHashMap;
import java.util.List;

public class Connect extends ControlPacket {

    private String protocolName;
    private ProtocolLevel protocolLevel;

    private int userNameFlag;
    private int passwordFlag;
    private int willRetain;
    private int willQoS;
    private int willFlag;
    private int cleanSessionFlag;
    private int reserved;
    private int keepAlive;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> connProperties;

    private String clientIdentifier;
    private String topic;
    private String message;
    /**
     * @since mqtt-v5.0
     */
    private byte[] payload;
    private String userName;
    private String password;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;


    public Connect(int fixedHeaderByte) {
        super(ControlPacketType.CONNECT, fixedHeaderByte);
    }

    public String getProtocolName() {
        return protocolName;
    }

    public void setProtocolName(String protocolName) {
        this.protocolName = protocolName;
    }

    public ProtocolLevel getProtocolLevel() {
        return protocolLevel;
    }

    public void setProtocolLevel(ProtocolLevel protocolLevel) {
        this.protocolLevel = protocolLevel;
    }

    public int getUserNameFlag() {
        return userNameFlag;
    }

    public void setUserNameFlag(int userNameFlag) {
        this.userNameFlag = userNameFlag;
    }

    public int getPasswordFlag() {
        return passwordFlag;
    }

    public void setPasswordFlag(int passwordFlag) {
        this.passwordFlag = passwordFlag;
    }

    public int getWillRetain() {
        return willRetain;
    }

    public void setWillRetain(int willRetain) {
        this.willRetain = willRetain;
    }

    public int getWillQoS() {
        return willQoS;
    }

    public void setWillQoS(int willQoS) {
        this.willQoS = willQoS;
    }

    public int getWillFlag() {
        return willFlag;
    }

    public void setWillFlag(int willFlag) {
        this.willFlag = willFlag;
    }

    public int getCleanSessionFlag() {
        return cleanSessionFlag;
    }

    public void setCleanSessionFlag(int cleanSessionFlag) {
        this.cleanSessionFlag = cleanSessionFlag;
    }

    public int getReserved() {
        return reserved;
    }

    public void setReserved(int reserved) {
        this.reserved = reserved;
    }

    public int getKeepAlive() {
        return keepAlive;
    }

    public void setKeepAlive(int keepAlive) {
        this.keepAlive = keepAlive;
    }

    public String getClientIdentifier() {
        return clientIdentifier;
    }

    public void setClientIdentifier(String clientIdentifier) {
        this.clientIdentifier = clientIdentifier;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public LinkedHashMap<PropertyType, PacketProperty> getConnProperties() {
        return connProperties;
    }

    public void setConnProperties(LinkedHashMap<PropertyType, PacketProperty> connProperties) {
        this.connProperties = connProperties;
    }

    public LinkedHashMap<PropertyType, PacketProperty> getProperties() {
        return properties;
    }

    public void setProperties(LinkedHashMap<PropertyType, PacketProperty> properties) {
        this.properties = properties;
    }

    /**
     * @since mqtt-v5.0
     */
    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }
}
