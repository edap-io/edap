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

import io.edap.json.decoders.ReflectDecoder;
import io.edap.json.enums.DataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsonCodecRegister {

    private Map<String, JsonDecoder> decoderMap = new ConcurrentHashMap<>();

    private JsonCodecRegister() {}

    public <T> JsonDecoder<T> getDecoder(Class<T> tClass, DataType dataType) {
        String key = tClass.getName() + "-" + dataType;
        JsonDecoder decoder = decoderMap.get(key);
        if (decoder == null) {
            decoder = new ReflectDecoder(tClass, dataType);
            decoderMap.put(key, decoder);
        }
        return decoder;
    }

    public static final JsonCodecRegister instance() {
        return JsonCodecRegister.SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final JsonCodecRegister INSTANCE = new JsonCodecRegister();
    }
}
