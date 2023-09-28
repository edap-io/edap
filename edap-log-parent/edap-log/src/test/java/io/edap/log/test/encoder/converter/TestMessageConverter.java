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

package io.edap.log.test.encoder.converter;

import io.edap.log.Converter;
import io.edap.log.LogEvent;
import io.edap.log.converter.MessageConverter;
import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestMessageConverter {

    @Test
    public void testConvert() {
        Converter converter = new MessageConverter("%msg");

        ByteArrayBuilder out = new ByteArrayBuilder();
        LogEvent logEvent = new LogEvent();
        logEvent.setFormat("name:{}, age:{}");
        logEvent.setArgv(new Object[]{"edap", 20});
        converter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "name:edap, age:20".getBytes());

        converter = new MessageConverter("%msg", "\n");

        out.reset();
        logEvent = new LogEvent();
        logEvent.setFormat("name:{}, age:{}");
        logEvent.setArgv(new Object[]{"edap", 20});
        converter.convertTo(out, logEvent);
        assertArrayEquals(out.toByteArray(), "name:edap, age:20\n".getBytes());
    }
}
