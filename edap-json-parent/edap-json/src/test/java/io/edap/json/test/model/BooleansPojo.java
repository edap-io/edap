/*
 * Copyright 2026 The edap Project
 *
 * The edap Project licenses this file to you under the Apache License,
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

package io.edap.json.test.model;

/**
 * boolean {@code isXxx} 字段 + {@code setXxx} setter 风格的 POJO。
 *
 * <p>覆盖 {@link io.edap.json.util.JsonUtil#getSetMethod} 的命名约定：
 *     字段名以 {@code is} + 大写字母开头时，setter 约定是去掉 {@code is} 前缀 + {@code set}。
 *     这是 Java Bean 规范对 boolean 字段的推荐命名（如 {@code boolean isTop; void setTop(boolean)}），
 *     但与普通 {@code setXxx} + 字段名直接对应（{@code boolean top; void setTop(boolean)}）不同。</p>
 */
public class BooleansPojo {

    /** boolean isTop → setter setTop（去 is 前缀） */
    private boolean isTop;

    /** boolean isActive → setter setActive（去 is 前缀） */
    private boolean isActive;

    /** Boolean wrapper 版本，验证同样识别 */
    private Boolean isVisible;

    public boolean isTop() {
        return isTop;
    }

    public void setTop(boolean top) {
        this.isTop = top;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        this.isActive = active;
    }

    public Boolean getIsVisible() {
        return isVisible;
    }

    public void setIsVisible(Boolean visible) {
        this.isVisible = visible;
    }
}
