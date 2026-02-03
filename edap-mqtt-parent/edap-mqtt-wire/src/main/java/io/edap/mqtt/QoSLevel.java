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

public enum QoSLevel {

    MOST_ONCE   (0),
    LEAST_ONCE  (1),
    EXACTLY_ONCE(2),
    RESERVED    (3);

    static QoSLevel[] LEVELS = new QoSLevel[] {
            MOST_ONCE,
            LEAST_ONCE,
            EXACTLY_ONCE,
            RESERVED
    };

    private int value;

    QoSLevel(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static QoSLevel fromValue(int value) {
        if (value >=0 && value < 4) {
            return LEVELS[value];
        }

        return RESERVED;
    }
}
