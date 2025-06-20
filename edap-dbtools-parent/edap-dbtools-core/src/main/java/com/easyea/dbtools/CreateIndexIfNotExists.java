package com.easyea.dbtools;

public interface CreateIndexIfNotExists {

    default boolean createIndexIfNotExists() {
        return true;
    }
}
