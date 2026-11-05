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

package io.edap.crypto.test.spi;

import io.edap.crypto.internal.CryptoDiagnostics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 捕获 {@link CryptoDiagnostics} 告警的测试替身。
 *
 * <p>edap-crypto <b>刻意不依赖 edap-log-api</b>（避免跨模块类初始化环，见
 * {@link CryptoDiagnostics} 的类注释），所以降级提示不再走 edap 的 logger，
 * 测试也就不能再用「捕获 logger」的方式断言 —— 只能捕获 {@code CryptoDiagnostics}
 * 的 sink。</p>
 */
public final class CapturingWarningSink implements CryptoDiagnostics.Sink {

    private final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    public void warn(String message) {
        messages.add(message);
    }

    /** 已捕获的全部告警（按发生顺序） */
    public List<String> messages() {
        return List.copyOf(messages);
    }

    /** 告警条数 */
    public int count() {
        return messages.size();
    }

    /** 是否存在包含指定片段的告警 */
    public boolean contains(String fragment) {
        for (String m : messages) {
            if (m != null && m.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 清空已捕获内容 */
    public void clear() {
        messages.clear();
    }
}
