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

/**
 * Bean转换的映射配置项
 * @param <O> 原属性的类信息
 * @param <D> 目标属性的类信息
 */
public class MapperConfig<O, D> {
    /**
     * 原属性名称
     */
    private String originalName;
    /**
     * 目标bean属性名称
     */
    private String destName;
    /**
     * 如果有需要特殊转换则指定转换的方法
     */
    private Convertor<O, D> convertor;
    /**
     * 如果属性为Bean则可以指定Bean转换的配置列表
     */
    private List<MapperConfig> mappperConfigs;

    /**
     * 静态构建配置信息的静态方法，方便构建配置项信息
     * @param orignalFieldName 原对象的Field名称
     * @param destFieldName 目标对象的Field名称
     * @return
     */
    public static MapperConfig mc(String orignalFieldName, String destFieldName) {
        return new MapperConfig(orignalFieldName, destFieldName);
    }

    /**
     * 静态构建配置信息的静态方法，方便构建配置项信息
     * @param orignalFieldName 原对象的Field名称
     * @param destFieldName 目标对象的Field名称
     * @param convertor 指定属性值转换的转换器
     * @return
     */
    public static <O, D> MapperConfig mc(String orignalFieldName, String destFieldName, Convertor<O, D> convertor) {
        return new MapperConfig(orignalFieldName, destFieldName, convertor);
    }

    /**
     * 静态构建配置信息的静态方法，方便构建配置项信息
     * @param orignalFieldName 原对象的Field名称
     * @param destFieldName 目标对象的Field名称
     * @param convertor 指定属性值转换的转换器
     * @param mapperConfigs 如果属性为JavaBean类型，则Bean转换器的配置列表
     * @return
     */
    public static <O, D> MapperConfig mc(String orignalFieldName, String destFieldName, Convertor<O, D> convertor, List<MapperConfig> mapperConfigs) {
        return new MapperConfig(orignalFieldName, destFieldName, convertor, mapperConfigs);
    }

    public MapperConfig() {

    }

    public MapperConfig(String orignalFieldName, String destFieldName) {
        this.originalName = orignalFieldName;
        this.destName = destFieldName;
    }

    public MapperConfig(String orignalFieldName, String destFieldName, Convertor<O, D> convertor) {
        this.originalName = orignalFieldName;
        this.destName = destFieldName;
        this.convertor = convertor;
    }

    public MapperConfig(String orignalFieldName, String destFieldName, Convertor<O, D> convertor, List<MapperConfig> mapperConfigs) {
        this.originalName = orignalFieldName;
        this.destName = destFieldName;
        this.convertor = convertor;
        this.mappperConfigs = mapperConfigs;
    }

    /**
     * 原属性名称
     */
    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    /**
     * 目标bean属性名称
     */
    public String getDestName() {
        return destName;
    }

    public void setDestName(String destName) {
        this.destName = destName;
    }

    /**
     * 如果有需要特殊转换则指定转换的方法
     */
    public Convertor<O, D> getConvertor() {
        return convertor;
    }

    public void setConvertor(Convertor<O, D> convertor) {
        this.convertor = convertor;
    }

    /**
     * 如果属性为Bean则可以指定Bean转换的配置列表
     */
    public List<MapperConfig> getMappperConfigs() {
        return mappperConfigs;
    }

    public void setMappperConfigs(List<MapperConfig> mappperConfigs) {
        this.mappperConfigs = mappperConfigs;
    }
}
