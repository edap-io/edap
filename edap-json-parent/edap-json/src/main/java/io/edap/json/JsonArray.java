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

import io.edap.json.util.JsonUtil;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

public class JsonArray<Object> extends ArrayList<Object> {

    public JsonArray() {
        super();
    }

    public JsonArray(int initCap) {
        super(initCap);
    }

    public JsonArray(List<Object> items) {
        super(items.size());
        addAll(items);
    }

    public String getString(int i) {
        Object o = get(i);
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String)o;
        }
        return String.valueOf(o);
    }

    public int getIntValue(int i) {
        return JsonUtil.getIntValue(get(i));
    }

    public long getLongValue(int i) {
        return JsonUtil.getLongValue(get(i));
    }

    public float getFloatValue(int i) {
        return JsonUtil.getFloatValue(get(i));
    }

    public float getFloat(int i) {
        return JsonUtil.getFloat(get(i));
    }

    public double getDoubleValue(int i) {
        return JsonUtil.getDoubleValue(get(i));
    }

    public double getDouble(int i) {
        return JsonUtil.getDouble(get(i));
    }

    public boolean getBooleanValue(int i) {
        return JsonUtil.getBooleanValue(get(i));
    }

    public boolean getBoolean(int i) {
        return JsonUtil.getBoolean(get(i));
    }

    public JsonObject getJsonObject(int i) {
        Object o = get(i);
        if (o == null) {
            return null;
        }
        if (o instanceof JsonObject) {
            return (JsonObject) o;
        }
        throw new JsonParseException("not json format");
    }

    public static JsonArray parseArray(String v) {
        StringJsonReader reader = new StringJsonReader(v);
        return reader.parseArray();
    }
}
