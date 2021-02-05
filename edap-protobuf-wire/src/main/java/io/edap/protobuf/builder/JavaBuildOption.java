/*
 * Copyright 2021 The edap Project
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

package io.edap.protobuf.builder;

public class JavaBuildOption {

    private String javaPackage;
    private String outerClassName;
    private String dtoPrefix;
    private boolean edapRpc;
    private boolean isNested;
    private boolean isMultipleFiles;
    private boolean chainOper;
    private boolean hasDefaultValue;

    /**
     * @return the javaPackage
     */
    public String getJavaPackage() {
        return javaPackage;
    }

    /**
     * @param javaPackage the javaPackage to set
     * @return
     */
    public JavaBuildOption setJavaPackage(String javaPackage) {
        this.javaPackage = javaPackage;
        return this;
    }

    /**
     * @return the outerClassName
     */
    public String getOuterClassName() {
        return outerClassName;
    }

    /**
     * @param outerClassName the outerClassName to set
     * @return
     */
    public JavaBuildOption setOuterClassName(String outerClassName) {
        this.outerClassName = outerClassName;
        return this;
    }

    /**
     * @return the isNested
     */
    public boolean isIsNested() {
        return isNested;
    }

    /**
     * @param isNested the isNested to set
     */
    public JavaBuildOption setIsNested(boolean isNested) {
        this.isNested = isNested;
        return this;
    }

    /**
     * @return the isMultipleFiles
     */
    public boolean isIsMultipleFiles() {
        return isMultipleFiles;
    }

    /**
     * @param isMultipleFiles the isMultipleFiles to set
     */
    public JavaBuildOption setIsMultipleFiles(boolean isMultipleFiles) {
        this.isMultipleFiles = isMultipleFiles;
        return this;
    }

    /**
     * @return the chainOper
     */
    public boolean isChainOper() {
        return chainOper;
    }

    /**
     * @param chainOper the chainOper to set
     */
    public void setChainOper(boolean chainOper) {
        this.chainOper = chainOper;
    }

    /**
     * @return the dtoPrefix
     */
    public String getDtoPrefix() {
        return dtoPrefix;
    }

    /**
     * @param dtoPrefix the dtoPrefix to set
     */
    public void setDtoPrefix(String dtoPrefix) {
        this.dtoPrefix = dtoPrefix;
    }

    /**
     * @return the hasDefaultValue
     */
    public boolean isHasDefaultValue() {
        return hasDefaultValue;
    }

    /**
     * @param hasDefaultValue the hasDefaultValue to set
     */
    public void setHasDefaultValue(boolean hasDefaultValue) {
        this.hasDefaultValue = hasDefaultValue;
    }

    public boolean isEdapRpc() {
        return edapRpc;
    }

    public void setEdapRpc(boolean edapRpc) {
        this.edapRpc = edapRpc;
    }
}
