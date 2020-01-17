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

package io.edap.protobuf.builder;

import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.Syntax;

/**
 * protocol buffer v3版本构建器
 */
public class ProtoV3Builder extends ProtoBuilder {

    public ProtoV3Builder(Proto proto) {
        super(proto);
    }

    @Override
    public String syntax() {
        Syntax syntax = proto.getSyntax();
        if (syntax != null && syntax == Syntax.PROTO_3) {
            return syntax.getValue();
        } else {
            return Syntax.PROTO_3.getValue();
        }
    }

    @Override
    public String fieldCardinality(Field.Cardinality cardinality) {
        if (cardinality == null) {
            return EMPTY;
        }
        switch (cardinality) {
            case REPEATED:
                return "repeated";
            case OPTIONAL:
                return "        ";
            default:
                return "        ";
        }
    }
}