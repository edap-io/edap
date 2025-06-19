package com.easyea.dbtools;

import com.easyea.dbtools.enums.DataType;

public interface DataTypeConvertor {
    default DataType convert(DataType dataType) {
        return dataType;
    }
}
