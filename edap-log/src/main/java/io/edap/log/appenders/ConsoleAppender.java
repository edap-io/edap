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

package io.edap.log.appenders;

import io.edap.log.io.BaseLogOutputStream;
import io.edap.log.io.ConsoleTarget;

import java.util.Arrays;

import static io.edap.log.helpers.Util.printError;

public class ConsoleAppender extends OutputStremAppender {

    protected ConsoleTarget target = ConsoleTarget.SystemOut;

    public void setTarget(String value) {
        ConsoleTarget t = ConsoleTarget.findByName(value.trim());
        if (t == null) {
            targetWarn(value);
        } else {
            target = t;
        }
    }

    @Override
    public void start() {
        BaseLogOutputStream targetStream = target.getStream();
        setOutputStream(targetStream);
        super.start();
    }

    public String getTarget() {
        return target.getName();
    }

    private void targetWarn(String val) {
        printError("[" + val + "] should be one of " + Arrays.toString(ConsoleTarget.values()));
    }
}
