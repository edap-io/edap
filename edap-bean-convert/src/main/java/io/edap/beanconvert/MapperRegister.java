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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bean转换器Mapper注册器，用来管理Bean的Mapper设置
 */
public class MapperRegister {

    ConvertorRegister convertorRegister = ConvertorRegister.instance();

    static final ConcurrentHashMap<String, MapperInfo> CONVERTOR_MAPPPER_CONFIGS = new ConcurrentHashMap<>();

    private MapperRegister() {

    }

    public void addMapper(Class orignalClazz, Class destClass, List<MapperConfig> configs) {
        String key = orignalClazz.getName() + "_" + destClass.getName();
        MapperInfo minfo = new MapperInfo();
        minfo.setOrignalClazz(orignalClazz);
        minfo.setDestClazz(destClass);
        minfo.setConfigList(configs);
        CONVERTOR_MAPPPER_CONFIGS.put(key, minfo);
        convertorRegister.clearConvertors();
    }

    public void addMapper(MapperInfo mapperInfo) {
        String key = mapperInfo.getOrignalClazz().getName() + "_" + mapperInfo.getOrignalClazz().getName();
        CONVERTOR_MAPPPER_CONFIGS.put(key, mapperInfo);
        convertorRegister.clearConvertors();
    }

    public MapperInfo getMapperInfo(Class orignalClazz, Class destClass) {
        String key = orignalClazz.getName() + "_" + destClass.getName();
        return CONVERTOR_MAPPPER_CONFIGS.get(key);
    }

    public static final MapperRegister instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final MapperRegister INSTANCE = new MapperRegister();
    }
}

