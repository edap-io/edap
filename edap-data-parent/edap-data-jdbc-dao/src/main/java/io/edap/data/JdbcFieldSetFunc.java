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

package io.edap.data;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 根据sql中字段列表，设置java对象属性的函数，只有在支持动态属性的jvm中使用
 * @param <T> java对象的类型
 */
@FunctionalInterface
public interface JdbcFieldSetFunc<T> {
    /**
     * 根据jdbc的ResultSet设置持久化Bean的属性值
     * @param t 需要设置属性的java对象
     * @param rs JDBC的ResultSet对象
     */
    void set(T t, ResultSet rs) throws SQLException;
}