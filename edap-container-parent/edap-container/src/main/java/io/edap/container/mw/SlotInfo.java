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

/**
 * {@link io.edap.container.Container#listSlots(String)} 返回的单个槽位状态。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code slot} —— PREVIOUS / CURRENT / STAGING，区分槽位语义</li>
 *   <li>{@code compositeVersion} —— composite version（含 SNAPSHOT 的 @buildTime 后缀），
 *       传给 {@code undeploy(appId, version)} / {@code switchVersion(appId, version)} 的就是这值</li>
 *   <li>{@code mavenVersion} —— Maven pom 里的 version，去掉 @buildTime 后缀，便于人类阅读；
 *       SNAPSHOT 下同 mavenVersion 可能多个 compositeVersion 并存</li>
 *   <li>{@code buildTime} —— Maven pom 里的 buildTime，SNAPSHOT 多 build 区分用；
 *       非 SNAPSHOT 时为 null</li>
 *   <li>{@code earName} —— 对应 .ear 文件名（含 T&lt;buildTime&gt;-&lt;version&gt;.ear 后缀），
 *       调试时定位磁盘文件用</li>
 * </ul>
 */
public class SlotInfo {

    private String slot;
    private String compositeVersion;
    private String mavenVersion;
    private String buildTime;
    private String earName;

    public String getSlot() {
        return slot;
    }

    public void setSlot(String slot) {
        this.slot = slot;
    }

    public String getCompositeVersion() {
        return compositeVersion;
    }

    public void setCompositeVersion(String compositeVersion) {
        this.compositeVersion = compositeVersion;
    }

    public String getMavenVersion() {
        return mavenVersion;
    }

    public void setMavenVersion(String mavenVersion) {
        this.mavenVersion = mavenVersion;
    }

    public String getBuildTime() {
        return buildTime;
    }

    public void setBuildTime(String buildTime) {
        this.buildTime = buildTime;
    }

    public String getEarName() {
        return earName;
    }

    public void setEarName(String earName) {
        this.earName = earName;
    }
}