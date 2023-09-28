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

package io.edap.log.helps;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 根据给定的变量值替换字符串中占位符相关功能封装
 */
public class MessageFormatter {

    static final String DELIM_STR = "{}";
    static final byte DELIM_START = '{';
    private static final byte ESCAPE_CHAR = '\\';

    public static void formatTo(ByteArrayBuilder bytesBuilder,
                                String messagePattern,
                                Object[] args) {
        if (args == null || args.length == 0) {
            bytesBuilder.append(messagePattern.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (messagePattern.equals("{}")) {
            if (args.length >= 1) {
                bytesBuilder.append(args[0]);
            }
            if (args.length > 1) {
                bytesBuilder.append((byte)'\n', (byte)'\t');
                if (args[1] instanceof Throwable) {
                    Throwable t = (Throwable)args[1];
                    bytesBuilder.append(t.getMessage()).append((byte)'\n');
                    printToBuilder(t, bytesBuilder);
                }
            }
            return;
        }
        int i = 0;
        int j;
        int L;
        for (L = 0; L < args.length; L++) {
            j = messagePattern.indexOf(DELIM_STR, i);
            if (j == -1) {
                // no more variables
                if (i == 0) { // this is a simple string
                    bytesBuilder.append(messagePattern.getBytes(StandardCharsets.UTF_8));
                } else { // add the tail string which contains no variables and return
                    // the result.
                    bytesBuilder.append(messagePattern, i, messagePattern.length() - i);
                }
                return;
            } else {
                if (isEscapedDelimeter(messagePattern, j)) {
                    if (!isDoubleEscaped(messagePattern, j)) {
                        L--; // DELIM_START was escaped, thus should not be incremented
                        bytesBuilder.append(messagePattern, i, j - 1);
                        bytesBuilder.append(DELIM_START);
                        i = j + 1;
                    } else {
                        // The escape character preceding the delimiter start is
                        // itself escaped: "abc x:\\{}"
                        // we have to consume one backward slash
                        bytesBuilder.append(messagePattern, i, j - 1);
                        bytesBuilder.append(args[L]);
                        i = j + 2;
                    }
                } else {
                    // normal case
                    bytesBuilder.append(messagePattern, i, j-i);
                    bytesBuilder.append(args[L]);
                    i = j + 2;
                }
            }
        }
        // append the characters following the last {} pair.
        bytesBuilder.append(messagePattern, i, messagePattern.length()-i);
    }

    final static boolean isEscapedDelimeter(String messagePattern, int delimeterStartIndex) {
        if (delimeterStartIndex == 0) {
            return false;
        }
        char potentialEscape = messagePattern.charAt(delimeterStartIndex - 1);
        if (potentialEscape == ESCAPE_CHAR) {
            return true;
        } else {
            return false;
        }
    }

    final static boolean isDoubleEscaped(String messagePattern, int delimeterStartIndex) {
        if (delimeterStartIndex >= 2 && messagePattern.charAt(delimeterStartIndex - 2) == ESCAPE_CHAR) {
            return true;
        } else {
            return false;
        }
    }

    private static void printToBuilder(Throwable throwable, ByteArrayBuilder builder) {
        for (StackTraceElement element : throwable.getStackTrace()) {
            builder.append((byte)'\t', (byte)'\t');
            builder.append(element.toString());
            builder.append((byte)'\n');
        }
    }
}
