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

package io.edap.log.test;

import io.edap.log.converter.BaseTextConverter;
import io.edap.log.converter.TextConverter;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.TextConverterFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestTextConverterGenerator {

    @Test
    public void testTextConverter() {
        String text = "] - ";
        TextConverter textConverter = TextConverterFactory.getTextConverter(text);
        ByteArrayBuilder builder = new ByteArrayBuilder();
        textConverter.convertTo(builder, null);
        String result = new String(builder.toByteArray());
        System.out.println(result);
        assertEquals(result, text);

        textConverter = TextConverterFactory.getTextConverter(text);
        builder = new ByteArrayBuilder();
        textConverter.convertTo(builder, null);
        result = new String(builder.toByteArray());
        System.out.println(result);
        System.out.println(textConverter.getClass().getName());
        assertEquals(result, text);
    }

    @Test
    public void testTextConverterFactory() {
        String text = "] - 123";
        TextConverter textConverter = TextConverterFactory.getTextConverter(text);
        ByteArrayBuilder builder = new ByteArrayBuilder();
        textConverter.convertTo(builder, null);
        String result = new String(builder.toByteArray());
        System.out.println(result);
        assertEquals(result, text);

        textConverter = TextConverterFactory.getTextConverter(text);
        builder = new ByteArrayBuilder();
        textConverter.convertTo(builder, null);
        result = new String(builder.toByteArray());
        System.out.println(result);
        assertEquals(textConverter.getClass().getName(), BaseTextConverter.class.getName());
        assertEquals(result, text);
    }
}
