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

import java.util.List;

public interface ViewDao<T> {

    List<T> query(String sql) throws Exception;

    List<T> query(String sql, QueryParam... params) throws Exception;

    List<T> query(String sql, Object... params) throws Exception;

    List<T> query(String sql, int start, int count) throws Exception;

    List<T> query(String sql, int start, int count, QueryParam... params) throws Exception;

    List<T> query(String sql, int start, int count, Object... params) throws Exception;

    PageResult<T> queryPage(String sql, String orderby, int pageNum, int pageSize) throws Exception;

    PageResult<T> queryPage(String sql, String orderBy, int pageNum, int pageSize, QueryParam... params) throws Exception;

    PageResult<T> queryPage(String sql, String orderBy, int pageNum, int pageSize, Object... params) throws Exception;

    T findById(Object id) throws Exception;

    T findOne(String sql) throws Exception;

    T findOne(String sql, QueryParam... params) throws Exception;

    T findOne(String sql, Object... params) throws Exception;
}
