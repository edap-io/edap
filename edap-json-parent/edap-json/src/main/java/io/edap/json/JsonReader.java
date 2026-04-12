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

package io.edap.json;

import io.edap.json.model.CommentItem;
import io.edap.json.model.DataRange;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

public interface JsonReader {

    DataRange readKeyRange();

    Object readObject();

    List<CommentItem> readComment();

    <T> T readObject(Class<T> valueType) throws InvocationTargetException, InstantiationException,
            IllegalAccessException;

    NodeType readStart();

    void nextPos(int count);

    char firstNotSpaceChar();

    String readString();

    int readInt();

    long readLong();

    boolean readBoolean();

    void skipValue();

    float readFloat();

    double readDouble();

    void reset();

    int keyHash();
}
