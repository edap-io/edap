/*
 * Copyright 2023 The edap Project
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package io.edap.log.test;

import com.lmax.disruptor.*;
import io.edap.log.LogArgsImpl;
import io.edap.log.LogConfig;
import io.edap.log.config.DisruptorConfig;
import io.edap.log.util.DisruptorUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.log.consts.LogConsts.DEFAULT_QUEUE_SIZE;
import static io.edap.log.util.DisruptorUtil.checkSetConfig;
import static org.junit.jupiter.api.Assertions.*;

public class TestDisruptorUtil {

    @Test
    public void testCheckSetConfig() {
        DisruptorConfig config = new DisruptorConfig();
        LogConfig.ArgNode arg = new LogConfig.ArgNode();
        arg.setName("capacity");
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> {
                    LogConfig.ArgNode arg2 = new LogConfig.ArgNode();
                    arg2.setName("capacity");
                    arg2.setValue("a");
                    checkSetConfig(config, arg2);
                });
        assertTrue(ex.getMessage().contains("For input string: \"a\""));
        assertEquals(config.getCapacity(), DEFAULT_QUEUE_SIZE);

        arg.setValue("2048");
        checkSetConfig(config, arg);
        assertEquals(config.getCapacity(), 2048);

        arg = new LogConfig.ArgNode();
        arg.setName("waitStrategy");
        ex = assertThrows(RuntimeException.class,
                () -> {
                    LogConfig.ArgNode arg2 = new LogConfig.ArgNode();
                    arg2.setName("waitStrategy");
                    arg2.setValue("test");
                    checkSetConfig(config, arg2);
                });
        assertTrue(ex.getMessage().contains("waitStrategy test is invalid!"));
        assertTrue(config.getWaitStrategy() instanceof SleepingWaitStrategy);

        arg.setValue("BlockingWaitStrategy");
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof BlockingWaitStrategy);

        arg.setValue("BusySpinWaitStrategy");
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof BusySpinWaitStrategy);

        arg.setValue("LiteBlockingWaitStrategy");
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof LiteBlockingWaitStrategy);

        Map<String, String> attrs = new HashMap<>();
        attrs.put("class", "LiteTimeoutBlockingWaitStrategy");
        List<LogConfig.ArgNode> children = new ArrayList<>();
        arg.setAttributes(attrs);

        LogConfig.ArgNode argParam1 = new LogConfig.ArgNode();
        argParam1.setName("arg");
        Map<String, String> paramAttrs = new HashMap<>();
        paramAttrs.put("type", "long");
        argParam1.setAttributes(paramAttrs);
        argParam1.setValue("5");
        children.add(argParam1);

        LogConfig.ArgNode argParam2 = new LogConfig.ArgNode();
        argParam2.setName("arg");
        Map<String, String> paramAttrs2 = new HashMap<>();
        paramAttrs2.put("type", "java.util.concurrent.TimeUnit");
        argParam2.setAttributes(paramAttrs2);
        argParam2.setValue("MILLISECONDS");
        children.add(argParam2);

        arg.setChilds(children);
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof LiteTimeoutBlockingWaitStrategy);

        attrs = new HashMap<>();
        attrs.put("class", "PhasedBackoffWaitStrategy");
        children = new ArrayList<>();
        arg.setAttributes(attrs);

        argParam1 = new LogConfig.ArgNode();
        argParam1.setName("arg");
        paramAttrs = new HashMap<>();
        paramAttrs.put("type", "long");
        argParam1.setAttributes(paramAttrs);
        argParam1.setValue("1");
        children.add(argParam1);

        argParam2 = new LogConfig.ArgNode();
        argParam2.setName("arg");
        paramAttrs = new HashMap<>();
        paramAttrs.put("type", "long");
        argParam2.setAttributes(paramAttrs);
        argParam2.setValue("2");
        children.add(argParam2);

        LogConfig.ArgNode argParam3 = new LogConfig.ArgNode();
        argParam3.setName("arg");
        Map<String, String> paramAttrs3 = new HashMap<>();
        paramAttrs3.put("type", "java.util.concurrent.TimeUnit");
        argParam3.setAttributes(paramAttrs3);
        argParam3.setValue("MILLISECONDS");
        children.add(argParam3);

        LogConfig.ArgNode argParam4 = new LogConfig.ArgNode();
        argParam4.setName("arg");
        argParam4.setValue("com.lmax.disruptor.BlockingWaitStrategy");
        children.add(argParam4);

        arg.setChilds(children);
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof PhasedBackoffWaitStrategy);

        arg.setChilds(null);
        arg.setAttributes(null);
        arg.setValue("SleepingWaitStrategy");
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof SleepingWaitStrategy);

        attrs = new HashMap<>();
        attrs.put("class", "TimeoutBlockingWaitStrategy");
        children = new ArrayList<>();
        arg.setAttributes(attrs);

        argParam1 = new LogConfig.ArgNode();
        argParam1.setName("arg");
        paramAttrs = new HashMap<>();
        paramAttrs.put("type", "long");
        argParam1.setAttributes(paramAttrs);
        argParam1.setValue("5");
        children.add(argParam1);

        argParam2 = new LogConfig.ArgNode();
        argParam2.setName("arg");
        paramAttrs2 = new HashMap<>();
        paramAttrs2.put("type", "java.util.concurrent.TimeUnit");
        argParam2.setAttributes(paramAttrs2);
        argParam2.setValue("MILLISECONDS");
        children.add(argParam2);

        arg.setChilds(children);
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof TimeoutBlockingWaitStrategy);

        arg.setAttributes(null);
        arg.setChilds(null);
        arg.setValue("YieldingWaitStrategy");
        checkSetConfig(config, arg);
        assertTrue(config.getWaitStrategy() instanceof YieldingWaitStrategy);
    }
}
