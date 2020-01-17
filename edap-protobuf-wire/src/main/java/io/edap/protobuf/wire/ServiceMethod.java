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

import io.edap.protobuf.wire.Service.ServiceType;

import java.util.List;

/**
 * rpc服务method的结构定义
 * @date : 2019/12/2
 */
public class ServiceMethod {
    /**
     * 服务定义Method的类型默认为 rpc
     */
    private ServiceType type;
    /**
     * 方法名
     */
    private String name;
    /**
     * 方法请求参数列表
     */
    private String request;
    /**
     * 方法返回参数的定义
     */
    private String response;
    /**
     * 方法块前的单行注释列表
     */
    private List<String> comments;
    /**
     * 方法行后的单行注释
     */
    private String comment;

    /**
     * 服务定义Method的类型默认为 rpc
     * @return the type
     */
    public Service.ServiceType getType() {
        return type;
    }

    /**
     * 服务定义Method的类型默认为 rpc
     * @param type the type to set
     * @return
     */
    public ServiceMethod setType(Service.ServiceType type) {
        this.type = type;
        return this;
    }

    /**
     * 方法名
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * 方法名
     * @param name the name to set
     * @return
     */
    public ServiceMethod setName(String name) {
        this.name = name;
        return this;
    }

    /**
     * 方法请求参数列表
     * @return the request
     */
    public String getRequest() {
        return request;
    }

    /**
     * 方法请求参数列表
     * @param request the request to set
     * @return
     */
    public ServiceMethod setRequest(String request) {
        this.request = request;
        return this;
    }

    /**
     * 方法返回参数的定义
     * @return the response
     */
    public String getResponse() {
        return response;
    }

    /**
     * 方法返回参数的定义
     * @param response the response to set
     * @return
     */
    public ServiceMethod setResponse(String response) {
        this.response = response;
        return this;
    }

    /**
     * 方法块前的单行注释列表
     * @return the comments
     */
    public List<String> getComments() {
        return comments;
    }

    /**
     * 方法块前的单行注释列表
     * @param comments the comments to set
     * @return
     */
    public ServiceMethod setComments(List<String> comments) {
        this.comments = comments;
        return this;
    }

    /**
     * 方法行后的单行注释
     * @return the comment
     */
    public String getComment() {
        return comment;
    }

    /**
     * 方法行后的单行注释
     * @param comment the comment to set
     * @return
     */
    public ServiceMethod setComment(String comment) {
        this.comment = comment;
        return this;
    }
}