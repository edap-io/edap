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

/**
 * 数据访问接口的定时
 * @param <T> 数据接口的水类型
 */
public interface EntityDao<T> {

    /**
     * 持久化一个javaBean
     * @param t 需要持久化的java对象
     * @return 返回存储后数据的行数
     * @throws Exception 抛出持久化时的异常信息
     */
    int insert(T t) throws Exception;

    /**
     * 持久化java对象列表
     * @param ts 需要持久化的对象列表
     * @return 返回每个对象对应的数据行数
     * @throws Exception 返回持久化时的异常
     */
    int[] insert(List<T> ts) throws Exception;

    /**
     * 根据查询条件返回java对象的列表，如果没有服务条件的对象则返回null
     * @param sql 查询数据的语句
     * @return 返回符合条件的java对象列表
     * @throws Exception 返回查询数据时的异常
     */
    List<T> query(String sql) throws Exception;

    /**
     * 根据查询语句已经语句绑定的变量，返回符合条件的java对象列表
     * @param sql 查询语句
     * @param params 查询语句绑定的变量数组
     * @return 符合条件的java对象列表
     * @throws Exception 查询时如果异常信息
     */
    List<T> query(String sql, QueryParam... params) throws Exception;

    /**
     * 根据查询语句已经语句绑定的变量，返回符合条件的java对象列表
     * @param sql 查询语句
     * @param params 查询语句绑定的变量数组
     * @return 符合条件的java对象列表
     * @throws Exception 查询时如果异常信息
     */
    List<T> query(String sql, Object... params) throws Exception;

    /**
     * 根据主键获取一个java对象
     * @param id 主键对象
     * @return 返回符合条件的java对象
     * @throws Exception 查询时抛出的异常
     */
    T findById(Object id) throws Exception;

    /**
     * 根据查询语句返回符合条件的第一个java对象，r如果有多个则返回第一个java对象
     * @param sql 查询条件
     * @return 返回符合条件的java对象
     * @throws Exception 查询时返回的异常信息
     */
    T findOne(String sql) throws Exception;

    /**
     * 根据查询语句一会查询语句绑定的变量列表，查询第一个符合条件的java对象
     * @param sql 查询语句
     * @param params 语句绑定的变量列表
     * @return 返回符合条件的第一个java对象
     * @throws Exception 查询时返回的异常信息
     */
    T findOne(String sql, QueryParam... params) throws Exception;

    /**
     * 根据查询语句一会查询语句绑定的变量列表，查询第一个符合条件的java对象
     * @param sql 查询语句
     * @param params 语句绑定的变量列表
     * @return 返回符合条件的第一个java对象
     * @throws Exception 查询时返回的异常信息
     */
    T findOne(String sql, Object... params) throws Exception;

    /**
     * 将java对象更新都持久化的介质中，按对象的主键更新
     * @param entity 需要更新的java对象
     * @return 更新记录的条数
     * @throws Exception
     */
    int updateById(T entity) throws Exception;

    /**
     * 根据语句更新符合条件的持久化对象的值
     * @param sql 更新语句
     * @return 更新成功的条数
     * @throws Exception 更新时抛出的异常
     */
    int update(String sql) throws Exception;

    /**
     * 根据语句以及语句绑定的变量更新久话介质中的值
     * @param sql 更新语句
     * @param params 更新语句绑定的变量
     * @return 返回更新的条数
     * @throws Exception 更新时抛出的异常s
     */
    int update(String sql, QueryParam... params) throws Exception;

    /**
     * 根据语句以及语句绑定的变量更新久话介质中的值
     * @param sql 更新语句
     * @param params 更新语句绑定的变量
     * @return 返回更新的条数
     * @throws Exception 更新时抛出的异常s
     */
    int update(String sql, Object... params) throws Exception;

    /**
     * 根据删除的语句从持久化介质中删除符合条件的数据
     * @param sql 删除的语句
     * @return 返回删除的记录条数
     * @throws Exception 删除时抛出的异常
     */
    int delete(String sql) throws Exception;

    /**
     * 根据删除语句以及删除语句绑定的变量来删除符合条件的记录
     * @param sql 删除的语句
     * @param param 删除语句绑定的变量
     * @return 删除记录的条数
     * @throws Exception 删除时抛出的异常
     */
    int delete(String sql, QueryParam... param) throws Exception;

    /**
     * 根据删除语句以及删除语句绑定的变量来删除符合条件的记录
     * @param sql 删除的语句
     * @param param 删除语句绑定的变量
     * @return 删除记录的条数
     * @throws Exception 删除时抛出的异常
     */
    int delete(String sql, Object... param) throws Exception;
}
