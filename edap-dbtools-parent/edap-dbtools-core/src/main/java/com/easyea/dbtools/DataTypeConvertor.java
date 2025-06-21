package com.easyea.dbtools;

import com.easyea.dbtools.enums.DataType;
import com.easyea.dbtools.enums.DbType;

public interface DataTypeConvertor {
    default DataType convert(DbType dbType, DataType dataType) {
        return dataType;
    }
}
