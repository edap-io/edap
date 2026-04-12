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

package io.edap.protobuf.idl.model;

import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Option;

import java.util.List;

public class IdlFieldType {
    private String type;
    private Field.Cardinality cardinality;
    private List<Option> options;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Field.Cardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(Field.Cardinality cardinality) {
        this.cardinality = cardinality;
    }

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options;
    }
}
