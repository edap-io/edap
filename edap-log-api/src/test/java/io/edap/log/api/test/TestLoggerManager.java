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

package io.edap.log.api.test;

import io.edap.log.Logger;
import io.edap.log.LoggerFactory;
import io.edap.log.LoggerManager;
import io.edap.log.NopLogger;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static com.github.stefanbirkner.systemlambda.SystemLambda.*;
import static io.edap.log.LoggerManager.getSpiProviderName;
import static io.edap.log.LoggerManager.setSpiProviderName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestLoggerManager {

    @Test
    public void testGetLogger() throws NoSuchFieldException, IllegalAccessException {
        Field loggerFactoryField = LoggerManager.class.getDeclaredField("LOGGER_FACTORY");
        loggerFactoryField.setAccessible(true);
        LoggerFactory factory = (LoggerFactory) loggerFactoryField.get(null);
        if (factory != null) {
            loggerFactoryField.set(null, null);
        }

        Field initedFied = LoggerManager.class.getDeclaredField("INITED");
        initedFied.setAccessible(true);
        Boolean inited = (Boolean)initedFied.get(null);
        if (inited == null) {
            initedFied.set(null, true);
        }

        Logger log = LoggerManager.getLogger(TestLoggerManager.class);
        assertEquals(NopLogger.class.getName(), log.getClass().getName());
        System.out.println(log);

        initedFied.set(null, null);
        log = LoggerManager.getLogger(TestLoggerManager.class);
        assertEquals(NopLogger.class.getName(), log.getClass().getName());
        System.out.println(log);

        log = LoggerManager.getLogger(TestLoggerManager.class);
        assertEquals(NopLogger.class.getName(), log.getClass().getName());
        System.out.println(log);
    }

    @Test
    public void testGetSpiProviderName() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException {
        setSpiProviderName(null);
        String spiName = getSpiProviderName();
        assertEquals(spiName, null);

        Method initMethod = LoggerManager.class.getDeclaredMethod("init", new Class[]{});
        initMethod.setAccessible(true);
        initMethod.invoke(null, new Object[]{});
        Field loggerFactoryField = LoggerManager.class.getDeclaredField("LOGGER_FACTORY");
        loggerFactoryField.setAccessible(true);
        LoggerFactory factory = (LoggerFactory) loggerFactoryField.get(null);
        if (factory != null) {
            loggerFactoryField.set(null, null);
        }


        String setSpiName = "io.edap.log.NopLogger";
        setSpiProviderName(setSpiName);
        spiName = getSpiProviderName();
        assertEquals(spiName, setSpiName);

        setSpiName = "io.edap.log.api.test.spi.TestLoggerServiceProvider";
        setSpiProviderName(setSpiName, false);
        spiName = getSpiProviderName();
        assertEquals(spiName, setSpiName);
    }

    @Test
    public void testGetSysSpiProviderName() throws Exception {
        Method method = LoggerManager.class.getDeclaredMethod("getSysSpiProviderName");
        method.setAccessible(true);
        String name = (String)method.invoke(null);
        assertNull(name);

        System.setProperty("edap_log_spi_provider", "");
        name = (String)method.invoke(null);
        assertNull(name);

        name = withEnvironmentVariable("edap_log_spi_provider", "io.edap.log.NopLogger2")
                .execute(() -> (String)method.invoke(null));
        System.out.println("env spiName=" + name);
        assertEquals(name, "io.edap.log.NopLogger2");

        name = withEnvironmentVariable("edap_log_spi_provider2", "io.edap.log.NopLogger2")
                .execute(() -> (String)method.invoke(null));
        System.out.println("env spiName=" + name);
        assertNull(name, "io.edap.log.NopLogger2");

        String spiName = "io.edap.log.spi.EdapLogServiceProvider";
        System.setProperty("edap_log_spi_provider", spiName);
        name = (String)method.invoke(null);
        assertEquals(name, spiName);
    }


}
