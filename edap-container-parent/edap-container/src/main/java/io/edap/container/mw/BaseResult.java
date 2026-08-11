/*
 * Copyright 2023 The edap Project
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

package io.edap.container.mw;

public class BaseResult<T> {

    /** 成功 code（0）；失败 code 由调用方传入（业务自定义 code）。 */
    public static final int SUCCESS = 0;

    private int code;
    private String message;
    private T data;

    public boolean isSuccess() {
        return code == 0;
    }

    public static BaseResult fail(int code, String message) {
        BaseResult r = new BaseResult();
        r.setCode(code);
        r.setMessage(message);

        return r;
    }

    public static BaseResult success(String message) {
        BaseResult r = new BaseResult();
        r.setCode(SUCCESS);
        r.setMessage(message);
        return r;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
