/*
 * Copyright 2023 The edap Project
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

package io.edap.log.io;

import java.io.IOException;
import java.io.OutputStream;

public enum ConsoleTarget {

    SystemOut("System.out", new BaseLogOutputStream(System.out)),

    SystemErr("System.err", new BaseLogOutputStream(System.err));

    public static ConsoleTarget findByName(String name) {
        for (ConsoleTarget target : ConsoleTarget.values()) {
            if (target.name.equalsIgnoreCase(name)) {
                return target;
            }
        }
        return null;
    }

    private final String name;
    private final BaseLogOutputStream stream;

    ConsoleTarget(String name, BaseLogOutputStream stream) {
        this.name = name;
        this.stream = stream;
    }

    public String getName() {
        return name;
    }

    public BaseLogOutputStream getStream() {
        return stream;
    }

    @Override
    public String toString() {
        return name;
    }
}
