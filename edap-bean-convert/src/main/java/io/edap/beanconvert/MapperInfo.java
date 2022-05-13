/*
 * Copyright 2022 The edap Project
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

package io.edap.beanconvert;

import java.util.ArrayList;
import java.util.List;

/**
 * Bean转换的映射配置信息，记录原Class，目标Class，以及field的配置列表
 */
public class MapperInfo {
    /**
     * 原Bean的Class实例
     */
    private Class orignalClazz;
    /**
     * 目标Bean的class实例
     */
    private Class destClazz;
    /**
     * 属性映射配置列表
     */
    private List<MapperConfig> configList;

    public static MapperInfo mi(Class orignalClazz, Class destClazz, MapperConfig... configs) {
        List<MapperConfig> list = null;
        if (configs != null && configs.length > 0) {
            list = new ArrayList<>(configs.length);
            for (MapperConfig mc : configs) {
                list.add(mc);
            }
        }
        return new MapperInfo(orignalClazz, destClazz, list);
    }

    public MapperInfo() {

    }

    public MapperInfo(Class orignalClazz, Class destClazz, List<MapperConfig> configs) {
        this.orignalClazz = orignalClazz;
        this.destClazz = destClazz;
        this.configList = configs;
    }

    public Class getOrignalClazz() {
        return orignalClazz;
    }

    public void setOrignalClazz(Class orignalClazz) {
        this.orignalClazz = orignalClazz;
    }

    public Class getDestClazz() {
        return destClazz;
    }

    public void setDestClazz(Class destClazz) {
        this.destClazz = destClazz;
    }

    public List<MapperConfig> getConfigList() {
        return configList;
    }

    public void setConfigList(List<MapperConfig> configList) {
        this.configList = configList;
    }
}
