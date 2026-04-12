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
import java.util.Map;

/**
 *
 * @author louis
 */
public interface MapDao {
    
    /**
     * 获取一个Map的列表，用于一些简单结果集的数据返回
     * @param sql 查询结果集的SQL语句
     * @return
     * @throws Exception
     */
    List<Map<String, Object>> getList(String sql) throws Exception;
    
    /**
     * 根据查询的SQL语句以及绑定的参数获取简单的记录集
     * @param sql 查询的SQL语句
     * @param params 绑定的参数
     * @return
     * @throws Exception
     */
    List<Map<String, Object>> getList(String sql, QueryParam... params) throws Exception;

    /**
     * 根据查询的SQL语句以及绑定的参数获取简单的记录集
     * @param sql 查询的SQL语句
     * @param params 绑定的参数
     * @return
     * @throws Exception
     */
    List<Map<String, Object>> getList(String sql, Object... params) throws Exception;

    /**
     * 获取一个单条记录的简单的Map字段名为键，字段的值为Map的值
     * @param sql 查询的SQL语句
     * @return
     * @throws Exception
     */
    Map<String, Object> getMap(String sql) throws Exception;

    /**
     * 根据查询SQL以及绑定的参数类型获取一个单条数据的Map
     * @param sql 查询的SQL语句
     * @param params 绑定的参数
     * @return
     * @throws Exception
     */
    Map<String, Object> getMap(String sql, QueryParam... params) throws Exception;

    /**
     * 根据查询SQL以及绑定的参数类型获取一个单条数据的Map
     * @param sql 查询的SQL语句
     * @param params 绑定的参数
     * @return
     * @throws Exception
     */
    Map<String, Object> getMap(String sql, Object... params) throws Exception;

    /**
     * 执行insert 或者更新ddl相关sql的操作
     * @param sql 要执行的sql语句
     * @return 如果为添加和更新记录则返回更新行数
     * @throws Exception 
     */
    int update(String sql) throws Exception;
    /**
     * 执行update、insert以及ddl相关sql的操作 
     * @param sql 要执行的sql语句
     * @param params 绑定位置的参数以及对应值
     * @return 如果为添加和更新记录则返回更新行数
     * @throws Exception 
     */
    int update(String sql, QueryParam... params) throws Exception;

    /**
     * 执行update、insert以及ddl相关sql的操作
     * @param sql 要执行的sql语句
     * @param params 绑定位置的参数以及对应值
     * @return 如果为添加和更新记录则返回更新行数
     * @throws Exception
     */
    int update(String sql, Object... params) throws Exception;
}
