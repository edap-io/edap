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

package com.easyea.dbtools.enums;

public enum DataType {

    INT(              "INT"              ),
    INTEGER(          "INTEGER"          ),
    TINYINT(          "TINYINT"          ),
    SMALLINT(         "SMALLINT"         ),
    MEDIUMINT(        "MEDIUMINT"        ),
    BIGINT(           "BIGINT"           ),
    UNSIGNED_BIG_INT( "UNSIGNED BIG INT" ),
    INT2(             "INT2"             ),
    INT8(             "INT8"             ),
    CHARACTER(        "CHARACTER"        ),
    VARCHAR(          "VARCHAR"          ),
    VARYING_CHARACTER("VARYING CHARACTER"),
    NCHAR(            "NCHAR"            ),
    NATIVE_CHARACTER( "NATIVE CHARACTER" ),
    NVARCHAR(         "NVARCHAR"         ),
    TEXT(             "TEXT"             ),
    CLOB(             "CLOB"             ),
    BLOB(             "BLOB"             ),
    REAL(             "REAL"             ),
    DOUBLE(           "DOUBLE"           ),
    DOUBLE_PRECISION( "DOUBLE PRECISION" ),
    FLOAT(            "FLOAT"            ),
    NUMERIC(          "NUMERIC"          ),
    DECIMAL(          "DECIMAL"          ),
    BOOLEAN(          "BOOLEAN"          ),
    DATE(             "DATE"             ),
    DATETIME(         "DATETIME"         );


    private String type;
    private int    precision;
    private int    scale;

    DataType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public void setPrecision(int precision) {
        this.precision = precision;
    }

    public int getPrecision() {
        return precision;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public int getScale() {
        return scale;
    }

}
