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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

public enum TomlType {

    STRING("string", String.class),
    INTEGER("integer", Long.class),
    FLOAT("float", Double.class),
    BOOLEAN("boolean", Boolean.class),
    OFFSET_DATE_TIME("offset date-time", OffsetDateTime.class),
    LOCAL_DATE_TIME("local date-time", LocalDateTime.class),
    LOCAL_DATE("local date", LocalDate.class),
    LOCAL_TIME("local time", LocalTime.class),
    ARRAY("array", TomlArray.class),
    TABLE("map", TomlMap.class);

    private final String name;
    private final Class<?> clazz;

    TomlType(String name, Class<?> clazz) {
        this.name = name;
        this.clazz = clazz;
    }

    public static String typeNameFor(Object value) {
        return "";
    }
}
