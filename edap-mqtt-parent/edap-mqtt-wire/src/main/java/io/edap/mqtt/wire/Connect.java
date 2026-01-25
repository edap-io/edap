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

import java.util.HashMap;
import java.util.Map;

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

    private String clientIdentifier;
    private String topic;
    private String message;
    private String userName;
    private String password;
    private byte[] payload;

    private int sessionExpiryInterval;
    private int receiveMaximum;
    private int maximumPacketSize;
    private int topicAliasMaximum;
    private int requestResponse;
    private int requestProblemInfo;
    private Map<String, String> headerUserProperty;
    private String authMethod;
    private byte[] authData;

    private int willDelayInterval;
    private int payloadFormatIndicator;
    private int messageExpiryInterval;
    private String contentType;
    private String responseTopic;
    private byte[] correlationData;
    private Map<String, String> userProperty;


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

    public int getSessionExpiryInterval() {
        return sessionExpiryInterval;
    }

    public void setSessionExpiryInterval(int sessionExpiryInterval) {
        this.sessionExpiryInterval = sessionExpiryInterval;
    }

    public int getReceiveMaximum() {
        return receiveMaximum;
    }

    public void setReceiveMaximum(int receiveMaximum) {
        this.receiveMaximum = receiveMaximum;
    }

    public int getMaximumPacketSize() {
        return maximumPacketSize;
    }

    public void setMaximumPacketSize(int maximumPacketSize) {
        this.maximumPacketSize = maximumPacketSize;
    }

    public int getTopicAliasMaximum() {
        return topicAliasMaximum;
    }

    public void setTopicAliasMaximum(int topicAliasMaximum) {
        this.topicAliasMaximum = topicAliasMaximum;
    }

    public int getRequestResponse() {
        return requestResponse;
    }

    public void setRequestResponse(int requestResponse) {
        this.requestResponse = requestResponse;
    }

    public int getRequestProblemInfo() {
        return requestProblemInfo;
    }

    public void setRequestProblemInfo(int requestProblemInfo) {
        this.requestProblemInfo = requestProblemInfo;
    }

    public Map<String, String> getHeaderUserProperty() {
        return headerUserProperty;
    }

    public void putHeaderUserProperty(String key, String value) {
        if (headerUserProperty == null) {
            headerUserProperty = new HashMap<>();
        }
        headerUserProperty.put(key, value);
    }

    public void setHeaderUserProperty(Map<String, String> headerUserProperty) {
        this.headerUserProperty = headerUserProperty;
    }

    public String getAuthMethod() {
        return authMethod;
    }

    public void setAuthMethod(String authMethod) {
        this.authMethod = authMethod;
    }

    public byte[] getAuthData() {
        return authData;
    }

    public void setAuthData(byte[] authData) {
        this.authData = authData;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public int getWillDelayInterval() {
        return willDelayInterval;
    }

    public void setWillDelayInterval(int willDelayInterval) {
        this.willDelayInterval = willDelayInterval;
    }

    public int getPayloadFormatIndicator() {
        return payloadFormatIndicator;
    }

    public void setPayloadFormatIndicator(int payloadFormatIndicator) {
        this.payloadFormatIndicator = payloadFormatIndicator;
    }

    public int getMessageExpiryInterval() {
        return messageExpiryInterval;
    }

    public void setMessageExpiryInterval(int messageExpiryInterval) {
        this.messageExpiryInterval = messageExpiryInterval;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getResponseTopic() {
        return responseTopic;
    }

    public void setResponseTopic(String responseTopic) {
        this.responseTopic = responseTopic;
    }

    public byte[] getCorrelationData() {
        return correlationData;
    }

    public void setCorrelationData(byte[] correlationData) {
        this.correlationData = correlationData;
    }

    public Map<String, String> getUserProperty() {
        return userProperty;
    }

    public void setUserProperty(Map<String, String> userProperty) {
        this.userProperty = userProperty;
    }

    public void putUserProperty(String key, String value) {
        if (userProperty == null) {
            userProperty = new HashMap<>();
        }
        userProperty.put(key, value);
    }
}
