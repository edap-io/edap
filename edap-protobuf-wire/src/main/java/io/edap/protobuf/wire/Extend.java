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

package io.edap.protobuf.wire;

import java.util.ArrayList;
import java.util.List;

/**
 * protocol buffer扩展信息的定义
 */
public class Extend {
    private Comment comment;
    private String name;
    private List<Field> fields;

    public Extend setName(String name) {
        this.name = name;
        return this;
    }

    public String getName() {
        return name;
    }

    public Extend setComment(Comment comment) {
        this.comment = comment;
        return this;
    }

    public Comment getComment() {
        return comment;
    }

    public Extend addField(Field field) {
        if (field == null) {
            return this;
        }
        getFields().add(field);
        return this;
    }

    public List<Field> getFields() {
        if (fields == null) {
            fields = new ArrayList<>();
        }
        return fields;
    }

    public void setFields(List<Field> fields) {
        if (fields instanceof ArrayList) {
            this.fields = fields;
        } else {
            getFields().addAll(fields);
        }
    }
}