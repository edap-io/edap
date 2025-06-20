package com.easyea.dbtools;

public interface PrimaryKeyNotNull {

    default boolean enablePrimaryKeyNotNull() {
        return true;
    }
}
