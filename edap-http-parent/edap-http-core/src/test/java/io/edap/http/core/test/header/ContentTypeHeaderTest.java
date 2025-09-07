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

import io.edap.http.header.ContentTypeHeader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ContentTypeHeaderTest {

    @Test
    public void testGetCharset() {
        ContentTypeHeader cth = ContentTypeHeader.JSON;

        assertEquals(cth.getCharset(), StandardCharsets.UTF_8);

        cth = ContentTypeHeader.PLAIN;
        assertNull(cth.getCharset());


        cth = ContentTypeHeader.from("application/xml");
        assertEquals(cth.getContentType(), "application/xml");
        assertNull(cth.getCharset());

        cth = ContentTypeHeader.from("application/xml");
        assertEquals(cth.getContentType(), "application/xml");
        assertNull(cth.getCharset());

        cth = ContentTypeHeader.from("application/xml; charset");
        assertEquals(cth.getContentType(), "application/xml");
        assertNull(cth.getCharset());

        cth = ContentTypeHeader.from("application/xml; charses");
        assertEquals(cth.getContentType(), "application/xml");
        assertNull(cth.getCharset());
    }
}
