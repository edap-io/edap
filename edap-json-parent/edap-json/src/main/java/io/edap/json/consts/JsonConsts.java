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

package io.edap.json.consts;

/**
 * Json使用的常用常数
 */
public class JsonConsts {

    private JsonConsts() {}

    public final static int INVALID_CHAR_FOR_NUMBER = -1;

    public final static int END_OF_NUMBER = -2;

    public final static byte[] POSITIVE_INFINITY_BYTES = "\"Infinity\"".getBytes();

    public final static byte[] NEGATIVE_INFINITY_BYTES = "\"-Infinity\"".getBytes();
}
