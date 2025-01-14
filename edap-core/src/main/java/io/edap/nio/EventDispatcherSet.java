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

package io.edap.nio;

import java.nio.channels.SelectionKey;
import java.util.AbstractSet;
import java.util.Iterator;

public class EventDispatcherSet extends AbstractSet<SelectionKey> {

    int size;

    NioEventDispatcher dispatcher;

    public EventDispatcherSet(NioEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean add(SelectionKey key) {
        if (key == null) {
            return false;
        }
        try {
            dispatcher.dispatch(key);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        size++;
        return true;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean remove(Object o) {
        return false;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public Iterator<SelectionKey> iterator() {
        throw new UnsupportedOperationException();
    }

    public void reset() {
        reset(0);
    }

    public void reset(int start) {
        //Arrays.fill(keys, start, size, null);
        size = 0;
    }
}