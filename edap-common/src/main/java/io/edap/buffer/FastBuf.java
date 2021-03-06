/*
 * Copyright 2020 The edap Project
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

package io.edap.buffer;

import io.edap.util.UnsafeUtil;

public class FastBuf {

    private long endAddress;
    private long writePos;

    public int write(byte[] bs, int offset, int len) {
        int remain = (int)(endAddress - writePos);
        if (len > remain) {
            len = remain;
        }
        UnsafeUtil.copyMemory(bs, offset, writePos, len);
        writePos += len;
        return len;
    }
}
