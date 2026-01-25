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

public class Publish extends ControlPacket {

    private String topic;
    private int packetIdentifier;
    private String message;

    private int payloadFormatIndicator;
    private int messageExpiryInterval;
    private int topicAlias;
    private String responseTopic;
    private byte[] correlationData;
    private Map<String, String> userProperty;
    private int subscriptionIdentifier;
    private String contentType;
    private byte[] payload;

    public Publish(int fixedHeaderByte) {
        super(ControlPacketType.PUBLISH, fixedHeaderByte);
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
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

    public int getTopicAlias() {
        return topicAlias;
    }

    public void setTopicAlias(int topicAlias) {
        this.topicAlias = topicAlias;
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

    public int getSubscriptionIdentifier() {
        return subscriptionIdentifier;
    }

    public void setSubscriptionIdentifier(int subscriptionIdentifier) {
        this.subscriptionIdentifier = subscriptionIdentifier;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }
}
