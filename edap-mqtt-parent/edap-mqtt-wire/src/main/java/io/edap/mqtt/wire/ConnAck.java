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

public class ConnAck extends ControlPacket {

    private int connAckFlag;

    private int connAckCode;

    private int sessionExpiryInterval;
    private int receiveMaximum;
    private int maximumQoS;
    private int retainAvailable;
    private int maximumPacketSize;
    private String assignedClientIdentifier;
    private int topicAliasMaximum;
    private String reason;
    private Map<String, String> userProperty;
    private int wildcardSubscriptionAvailable;
    private int subscriptionIdentifiersAvailable;
    private int sharedSubscriptionAvailable;
    private int serverKeepAlive;
    private String responseInfo;
    private String serverReference;
    private String authMethod;
    private byte[] authData;

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

    public int getMaximumQoS() {
        return maximumQoS;
    }

    public void setMaximumQoS(int maximumQoS) {
        this.maximumQoS = maximumQoS;
    }

    public int getRetainAvailable() {
        return retainAvailable;
    }

    public void setRetainAvailable(int retainAvailable) {
        this.retainAvailable = retainAvailable;
    }

    public int getMaximumPacketSize() {
        return maximumPacketSize;
    }

    public void setMaximumPacketSize(int maximumPacketSize) {
        this.maximumPacketSize = maximumPacketSize;
    }

    public String getAssignedClientIdentifier() {
        return assignedClientIdentifier;
    }

    public void setAssignedClientIdentifier(String assignedClientIdentifier) {
        this.assignedClientIdentifier = assignedClientIdentifier;
    }

    public int getTopicAliasMaximum() {
        return topicAliasMaximum;
    }

    public void setTopicAliasMaximum(int topicAliasMaximum) {
        this.topicAliasMaximum = topicAliasMaximum;
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

    public void putUserProperty(String key, String value) {
        if (userProperty == null) {
            userProperty = new HashMap<>();
        }
        userProperty.put(key, value);
    }

    public int getWildcardSubscriptionAvailable() {
        return wildcardSubscriptionAvailable;
    }

    public void setWildcardSubscriptionAvailable(int wildcardSubscriptionAvailable) {
        this.wildcardSubscriptionAvailable = wildcardSubscriptionAvailable;
    }

    public int getSubscriptionIdentifiersAvailable() {
        return subscriptionIdentifiersAvailable;
    }

    public void setSubscriptionIdentifiersAvailable(int subscriptionIdentifiersAvailable) {
        this.subscriptionIdentifiersAvailable = subscriptionIdentifiersAvailable;
    }

    public int getSharedSubscriptionAvailable() {
        return sharedSubscriptionAvailable;
    }

    public void setSharedSubscriptionAvailable(int sharedSubscriptionAvailable) {
        this.sharedSubscriptionAvailable = sharedSubscriptionAvailable;
    }

    public int getServerKeepAlive() {
        return serverKeepAlive;
    }

    public void setServerKeepAlive(int serverKeepAlive) {
        this.serverKeepAlive = serverKeepAlive;
    }

    public String getResponseInfo() {
        return responseInfo;
    }

    public void setResponseInfo(String responseInfo) {
        this.responseInfo = responseInfo;
    }

    public String getServerReference() {
        return serverReference;
    }

    public void setServerReference(String serverReference) {
        this.serverReference = serverReference;
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
}
