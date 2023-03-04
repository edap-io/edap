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

package io.edap.toml;

import java.util.List;
import java.util.StringJoiner;

public class Toml {

    static boolean[] SIMPLE_KEY_CHARS = new boolean[123];
    static {
        for (int i=0;i<123;i++) {
            SIMPLE_KEY_CHARS[i] = false;
        }
        for (int i='a';i<='z';i++) {
            SIMPLE_KEY_CHARS[i] = true;
        }
        for (int i='A';i<='Z';i++) {
            SIMPLE_KEY_CHARS[i] = true;
        }
        for (int i='0';i<='9';i++) {
            SIMPLE_KEY_CHARS[i] = true;
        }
        SIMPLE_KEY_CHARS['-'] = true;
        SIMPLE_KEY_CHARS['_'] = true;
    }

    public static String joinKeyPath(List<String> path) {
        StringJoiner key = new StringJoiner(".");
        for (String k : path) {
            if (isSimpleKey(k)) {
                key.add(k);
            } else {
                key.add("\"" + k + "\"");
            }
        }
        return key.toString();
    }

    public static boolean isSimpleKey(String key) {
        int len = key.length();
        for (int i=0;i<len;i++) {
            char c = key.charAt(i);
            if (c > 122) {
                return false;
            }
            if (!SIMPLE_KEY_CHARS[c]) {
                return false;
            }
        }
        return true;
    }
}
