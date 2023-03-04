/*
 * Copyright 2022 The edap Project
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

package io.edap.log.test;

import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static io.edap.log.helps.MessageFormatter.formatTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestMessageFormatter {

    @Test
    public void testFormatToByteArrayBuilder() throws IOException {
        String msg = "log message uid: {}, username: {}";
        ByteArrayBuilder builder = new ByteArrayBuilder();
        Object[] args = null;
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(), msg.getBytes(StandardCharsets.UTF_8));

        builder.reset();
        msg = "log msg uid:  username: ";
        args = new Object[]{123};
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(), msg.getBytes(StandardCharsets.UTF_8));

        builder.reset();
        msg = "log msg uid: {} username: ";
        args = new Object[]{123, "louis"};
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(),
                "log msg uid: 123 username: ".getBytes(StandardCharsets.UTF_8));

        builder.reset();
        msg = "log msg uid: {} username: {}";
        args = new Object[]{123, "louis"};
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(),
                "log msg uid: 123 username: louis".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testEmptyFormat() throws IOException {
        String msg = "{}";
        Object[] args = new Object[]{123};
        ByteArrayBuilder builder = new ByteArrayBuilder();
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(), "123".getBytes(StandardCharsets.UTF_8));

        builder.reset();
        args = new Object[]{"123", new Throwable("test Throwable")};
        formatTo(builder, msg, args);
        String[] msgList = new String(builder.toByteArray()).split("\n");
        assertEquals(msgList.length > 1, true);
        assertEquals(msgList[0], "123");
        assertEquals(msgList[1], "\ttest Throwable");
    }

    @Test
    public void testEscapedAppend() throws IOException {
        String msg = "\\{}log msg uid: {} username: {}";
        ByteArrayBuilder builder = new ByteArrayBuilder();
        Object[] args = new Object[]{123, "louis"};
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(), "{}log msg uid: 123 username: louis".getBytes(StandardCharsets.UTF_8));

        builder.reset();
        msg = "{}log msg uid: {} username: {}";
        args = new Object[]{"edap ", 123, "louis"};
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(), "edap log msg uid: 123 username: louis".getBytes(StandardCharsets.UTF_8));

        builder.reset();
        msg = "\\\\{}log msg uid: {} username: {}";
        args = new Object[]{"edap ", 123, "louis"};
        formatTo(builder, msg, args);
        assertArrayEquals(builder.toByteArray(), "\\edap log msg uid: 123 username: louis".getBytes(StandardCharsets.UTF_8));

    }
}
