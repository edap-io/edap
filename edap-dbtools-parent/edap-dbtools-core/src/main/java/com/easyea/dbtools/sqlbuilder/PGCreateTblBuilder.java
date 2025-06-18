package com.easyea.dbtools.sqlbuilder;

public class PGCreateTblBuilder extends CreateTblBuilder {

    @Override
    public boolean enableIfNotExists() {
        return true;
    }
}
