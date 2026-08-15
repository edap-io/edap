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

package io.edap.container;

import java.util.Objects;

/**
 * (targetIf, protoIf, methodName, annoType) 四元组，作为 {@link AppContext#generatedHandlers} 的 key。
 *
 * <p><b>为什么 key 含 targetIf</b>：同一 proto method 可能被多个协议路由
 * （如 {@code sayHello} 同时被 {@code @ProtoHttp} 和 {@code @ProtoWebSocket} 标注），
 * 不同 targetIf → 不同实现类（不同 typed 接口 + 不同协议提参 / 响应字节码），
 * 需要各自缓存、彼此互不干扰。</p>
 *
 * <p><b>为什么 key 用 protoIf + methodName 而非 java.lang.reflect.Method</b>：
 * Handler class 是"proto 接口 × capability × 方法"的产物，与实现类（{@code GreeterServiceImpl}）
 * 解耦——同一 proto 接口的多种实现（本地 bean / 远端 RPC / 暂未部署）共享同一份 Handler impl class。
 * 用 {@code Method} 作为 key 的一部分会把 declaringClass（实现类）带进 cache，导致：
 * <ul>
 *   <li>无本地 bean 时无法生成 Handler（无 Method 可得）</li>
 *   <li>多实现并存时重复生成同字节码，浪费 permgen/metaspace</li>
 * </ul>
 * </p>
 *
 * <p><b>annoType 加入 key 的原因</b>：同一 (targetIf, protoIf, methodName) 下，若方法同时标注两条协议注解
 * （如既挂 {@code @ProtoHttp} 又挂 {@code @ProtoWebSocket}），每条注解应产出一份独立
 * Handler impl class——annoType 区分这两份。</p>
 *
 * <p>PO 实现而非 record：避免对 record 反射 / equals 行为差异的耦合；equals / hashCode
 * 由 {@link Objects} 系列显式构造，便于子类化或调试期调整。</p>
 */
public final class HandlerKey {

    private final Class<?> targetIf;
    private final Class<?> protoIf;
    private final String   methodName;
    private final String   annoType;

    public HandlerKey(Class<?> targetIf, Class<?> protoIf, String methodName, String annoType) {
        this.targetIf   = targetIf;
        this.protoIf    = protoIf;
        this.methodName = methodName;
        this.annoType   = annoType;
    }

    public Class<?> targetIf()   { return targetIf;   }
    public Class<?> protoIf()    { return protoIf;    }
    public String   methodName() { return methodName; }
    public String   annoType()   { return annoType;   }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HandlerKey)) return false;
        HandlerKey k = (HandlerKey) o;
        return Objects.equals(targetIf,   k.targetIf)
            && Objects.equals(protoIf,    k.protoIf)
            && Objects.equals(methodName, k.methodName)
            && Objects.equals(annoType,   k.annoType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetIf, protoIf, methodName, annoType);
    }

    @Override
    public String toString() {
        return "HandlerKey{targetIf="   + targetIf.getName()
             + ", protoIf="    + (protoIf == null ? "null" : protoIf.getName())
             + ", methodName=" + methodName
             + ", annoType="   + annoType + "}";
    }
}
