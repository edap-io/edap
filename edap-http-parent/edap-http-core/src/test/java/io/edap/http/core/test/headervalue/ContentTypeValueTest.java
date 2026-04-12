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

package io.edap.http.core.test.headervalue;

import io.edap.http.HeaderValue;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.headervalue.ContentTypeValue;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ContentTypeValueTest {

    @Test
    public void testFromHeaderValue() {
        HeaderValue hv = new HeaderValue("application/x-www-form-urlencoded; charset=UTF-8".getBytes(StandardCharsets.UTF_8));
        ContentTypeValue ctv = ContentTypeValue.fromHeaderValue(hv);
        assertNotNull(ctv);


        hv = new HeaderValue("application/x-www-form-urlencoded; charset=UTF-8".getBytes(StandardCharsets.UTF_8));
        ctv = ContentTypeValue.fromHeaderValue(hv);
        assertNotNull(ctv);
        assertEquals(ctv.getContentType(), ContentTypeHeader.FORM_URLENCODED.getContentType());

        hv = new ContentTypeValue("application/x-www-form-urlencoded");
        ctv = ContentTypeValue.fromHeaderValue(hv);
        assertNotNull(ctv);
        assertEquals(ctv.getContentType(), ContentTypeHeader.FORM_URLENCODED.getContentType());

    }
}
