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

package io.edap.data.jdbc.jdbc.test.entity;


import io.edap.data.jdbc.annotation.Id;

import java.io.Serializable;

public class Demo implements Serializable {
    @Id
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private long createTime;
    private Long localDateTime;
    //private LocalDate localDate;
//    private Boolean isNew;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public Long getLocalDateTime() {
        return localDateTime;
    }

    public void setLocalDateTime(Long localDateTime) {
        this.localDateTime = localDateTime;
    }

//    public LocalDate getLocalDate() {
//        return localDate;
//    }
//
//    public void setLocalDate(LocalDate localDate) {
//        this.localDate = localDate;
//    }
}