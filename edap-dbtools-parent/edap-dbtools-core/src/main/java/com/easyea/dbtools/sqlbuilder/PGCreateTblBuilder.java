package com.easyea.dbtools.sqlbuilder;

import com.easyea.dbtools.CreateIndexIfNotExists;
import com.easyea.dbtools.DataTypeConvertor;
import com.easyea.dbtools.ColKeywordConvertor;
import com.easyea.dbtools.PrimaryKeyNotNull;
import com.easyea.dbtools.enums.DataType;

import static com.easyea.dbtools.enums.DataType.TEXT;

public class PGCreateTblBuilder extends CreateTblBuilder implements DataTypeConvertor, ColKeywordConvertor,
        CreateIndexIfNotExists, PrimaryKeyNotNull {

    @Override
    public boolean enableIfNotExists() {
        return true;
    }

    @Override
    public DataType convert(DataType dataType) {
        if (dataType == null) {
            return TEXT;
        }
        switch (dataType) {
            case BLOB:
                return DataType.BYTEA;
            case DATETIME:
                return DataType.TIMESTAMP_WITH_ZONE;
            default:
                return dataType;
        }
    }

    @Override
    public String colNameConvert(String colName) {
        if ("OFFSET".equalsIgnoreCase(colName)) {
            return "COl_" + colName;
        }
        return colName;
    }

    @Override
    public  boolean enablePrimaryKeyNotNull() {
        return false;
    }
}
