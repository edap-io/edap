/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.test.message.ext;

import java.util.List;
import java.util.Map;

public class ListMapFieldModel {

    private List<Map<String, Object>> listMap;

    public List<Map<String, Object>> getListMap() {
        return listMap;
    }

    public void setListMap(List<Map<String, Object>> listMap) {
        this.listMap = listMap;
    }
}
