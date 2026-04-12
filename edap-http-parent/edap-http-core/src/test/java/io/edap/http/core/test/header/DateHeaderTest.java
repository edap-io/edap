/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http.core.test.header;

import io.edap.http.HttpTime;
import io.edap.http.header.DateHeader;
import org.junit.jupiter.api.Test;


import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class DateHeaderTest {

    @Test
    public void testConstruct() {
        byte[] now = HttpTime.instance().getGMTBytes();
        byte[] key = "Date: ".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[now.length + key.length];
        System.arraycopy(key, 0, data, 0, key.length);
        System.arraycopy(now, 0, data, key.length, now.length);
        DateHeader header = new DateHeader();
        byte[] headerData = header.getBytes();
        assertArrayEquals(data, headerData);
    }
}
