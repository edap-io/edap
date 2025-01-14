/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.nio.impl;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.EdapSelectorInfo;
import io.edap.nio.EventDispatcherSet;
import io.edap.nio.NioEventDispatcher;
import io.edap.nio.SelectorProvider;
import io.edap.util.ClazzUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.Selector;

public class FastSelectorProvider implements SelectorProvider {

    private static final Logger LOG = LoggerManager.getLogger(FastSelectorProvider.class);

    private Boolean enableFastDispatch;

    @Override
    public boolean enableFastDispatch() {
        if (enableFastDispatch != null) {
            return enableFastDispatch;
        }
        try (Selector selector = Selector.open()) {
            Class<?> cls = selector.getClass();
            Field keysField    = ClazzUtil.getDeclaredField(cls, "selectedKeys");
            Field pubKeysField = ClazzUtil.getDeclaredField(cls, "publicSelectedKeys");

            if (!keysField.canAccess(selector)) {
                keysField.setAccessible(true);
                keysField.setAccessible(false);
            }
            if (!pubKeysField.canAccess(selector)) {
                pubKeysField.setAccessible(true);
                pubKeysField.setAccessible(false);
            }
            enableFastDispatch = true;
        } catch (IOException | NoSuchFieldException e) {
            enableFastDispatch = false;
            LOG.warn("Selector.open() error", e);
        }
        return enableFastDispatch;
    }

    @Override
    public EdapSelectorInfo openSelector(NioEventDispatcher dispatcher) throws IOException {
        EdapSelectorInfo info = new EdapSelectorInfo();
        try {
            Selector selector = Selector.open();
            Class<?> cls = selector.getClass();
            Field keysField    = ClazzUtil.getDeclaredField(cls, "selectedKeys");
            Field pubKeysField = ClazzUtil.getDeclaredField(cls, "publicSelectedKeys");
            boolean keysCanAccess    = keysField.canAccess(selector);
            boolean pubKeysCanAccess = pubKeysField.canAccess(selector);

            if (!keysCanAccess) {
                keysField.setAccessible(true);
            }
            if (!pubKeysCanAccess) {
                pubKeysField.setAccessible(true);
            }

            EventDispatcherSet eventDispatcherSet = new EventDispatcherSet(dispatcher);
            keysField.set(selector, eventDispatcherSet);
            pubKeysField.set(selector, eventDispatcherSet);

            if (!keysCanAccess) {
                keysField.setAccessible(false);
            }
            if (!pubKeysCanAccess) {
                pubKeysField.setAccessible(false);
            }
            info.setEventDispatcherSet(eventDispatcherSet);
            info.setSelector(selector);
            return info;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            LOG.warn("Selector reflect selectedKeys error", e);
        }
        return null;
    }
}
