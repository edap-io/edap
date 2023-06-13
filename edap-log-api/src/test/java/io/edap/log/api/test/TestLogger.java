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

package io.edap.log.api.test;

import io.edap.log.LogLevel;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestLogger {

    @Test
    public void testNopLogger() {
        Logger log = LoggerManager.getLogger(TestLogger.class);
        log.trace("Test Error msg");
        log.trace("Test error msg", new Throwable("Test throwable"));
        log.trace("Test error msg : uid:{}", z -> z.arg(123));

        log.debug("Test Error msg");
        log.debug("Test error msg", new Throwable("Test throwable"));
        log.debug("Test error msg : uid:{}", z -> z.arg(123));

        log.info("Test Error msg");
        log.info("Test error msg", new Throwable("Test throwable"));
        log.info("Test error msg : uid:{}", z -> z.arg(123));

        log.conf("Test Error msg");
        log.conf("Test error msg", new Throwable("Test throwable"));
        log.conf("Test error msg : uid:{}", z -> z.arg(123));

        log.warn("Test Error msg");
        log.warn("Test error msg", new Throwable("Test throwable"));
        log.warn("Test error msg : uid:{}", z -> z.arg(123));

        log.error("Test Error msg");
        log.error("Test error msg", new Throwable("Test throwable"));
        log.error("Test error msg : uid:{}", z -> z.arg(123));

        assertEquals(log.level(), 0);
        assertEquals(log.isEnabled(0), false);
        log.level(LogLevel.TRACE);
    }
}
