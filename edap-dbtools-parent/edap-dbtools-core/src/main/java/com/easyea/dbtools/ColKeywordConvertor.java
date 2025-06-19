package com.easyea.dbtools;

public interface ColKeywordConvertor {

    default String colNameConvert(String columnName) {
        return columnName;
    }
}
