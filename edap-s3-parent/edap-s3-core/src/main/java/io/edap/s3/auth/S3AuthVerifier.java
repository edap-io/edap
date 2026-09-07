/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.auth;

import io.edap.s3.model.S3Request;

import java.util.Map;

/**
 * S3 鉴权 SPI —— 验证 Authorization header / presigned URL 签名。
 *
 * <p>由 {@code S3HttpHandler}(server 模块)在 dispatch 前调用:
 * <pre>{@code
 * authVerifier.verify(req.method(), req.getPathInfo(), headers, queryParams, parsed);
 * }</pre>
 *
 * <p>core 模块零 HTTP 依赖 —— 入参全部原始类型(Map / String),
 * 由 server 模块的 S3HttpHandler 负责从 {@code HttpRequest} 抽取。
 *
 * <p>失败抛 {@link io.edap.s3.error.S3Exception}:
 * <ul>
 *   <li>SIGNATURE_DOES_NOT_MATCH —— 签名数学不对</li>
 *   <li>INVALID_ACCESS_KEY_ID —— access key id 不存在</li>
 *   <li>ACCESS_DENIED —— scope 不匹配 / bucket 不在该 key 权限内 / 时间偏差过大</li>
 * </ul>
 */
public interface S3AuthVerifier {

    /**
     * @param httpMethod       HTTP 动词(GET / PUT / POST / DELETE / HEAD)
     * @param path             canonical URI(已折叠双 slash;无 query string)
     * @param queryParams      query 参数(key 不重复,S3 标准请求通常不重复)
     * @param headers          request headers(key lowercase,如 {@code host} / {@code x-amz-date})
     * @param parsed           已解析的 S3 request,用于取 bucket 名做 ACL 检查
     */
    void verify(String httpMethod,
                String path,
                Map<String, String> queryParams,
                Map<String, String> headers,
                S3Request parsed) throws Exception;

    /**
     * 是否允许匿名访问(无 Authorization header)。Phase 1 默认 false,
     * 所有请求必须签名。
     */
    default boolean allowAnonymous() {
        return false;
    }
}
