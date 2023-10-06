/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http;

import io.edap.util.E;
import io.edap.util.FastNum;

/**
 * HTTP响应的状态码
 */
public class ResponseStatusCode {

    /**
     * 协议规定的HTTP 响应状态码以及相应描述
     * Source: https://www.iana.org/assignments/http-status-codes/http-status-codes.xhtml
     */
    protected static final String[] STATUS_CODES = new String[]{
            "100 Continue",
            "101 Switching Protocols",
            "102 Processing",
            "103 Early Hints",

            "200 OK",
            "201 Created",
            "202 Accepted",
            "203 Non-Authoritative Information",
            "204 No Content",
            "205 Reset Content",
            "206 Partial Content",
            "207 Multi-Status",
            "208 Already Reported",
            "226 IM Used",

            "300 Multiple Choices",
            "301 Moved Permanently",
            "302 Found",
            "303 See Other",
            "304 Not Modified",
            "305 Use Proxy",
            "307 Temporary Redirect",
            "308 Permanent Redirect",

            "400 Bad Request",
            "401 Unauthorized",
            "402 Payment Required",
            "403 Forbidden",
            "404 Not Found",
            "405 Method Not Allowed",
            "406 Not Acceptable",
            "407 Proxy Authentication Required",
            "408 Request Timeout",
            "409 Conflict",
            "410 Gone",
            "411 Length Required",
            "412 Precondition Failed",
            "413 Payload Too Large",
            "414 URI Too Long",
            "415 Unsupported Media Type",
            "416 Range Not Satisfiable",
            "417 Expectation Failed",
            "421 Misdirected Request",
            "422 Unprocessable Entity",
            "423 Locked",
            "424 Failed Dependency",
            "425 Too Early",
            "426 Upgrade Required",
            "428 Precondition Required",
            "429 Too Many Requests",
            "431 Request Header Fields Too Large",
            "451 Unavailable For Legal Reasons",

            "500 Internal Server Error",
            "501 Not Implemented",
            "502 Bad Gateway",
            "503 Service Unavailable",
            "504 Gateway Timeout",
            "505 HTTP Version Not Supported",
            "506 Variant Also Negotiates",
            "507 Insufficient Storage",
            "508 Loop Detected",
            "510 Not Extended",
            "511 Network Authentication Required"
    };

    private static final byte[][] RESPONSES = new byte[600][];

    static {
        for (String sc : STATUS_CODES) {
            initStatusCode(sc);
        }
    }

    private static void initStatusCode(String sc) {
        String[] vs = sc.split(" ", 2);
        String resp = sc + "\r\n";
        int code = FastNum.num(vs[0]);
        RESPONSES[code] = resp.getBytes();
    }

    /** `
     * 根据状态码返回需要给客户端返回的response状态的byte数组，携带HTTP/1.1的部分以及"\r\n"
     * @param code 状态码
     * @return
     */
    public static byte[] get(int code) {
        if (RESPONSES[code] != null) {
            return RESPONSES[code];
        }
        throw E.rtEx("Invalid HTTP response code: " + code);
    }
}
