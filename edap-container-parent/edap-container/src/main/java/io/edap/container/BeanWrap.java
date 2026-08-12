package io.edap.container;

import io.edap.microservice.Scope;

public final class BeanWrap {
    private final BeanDef def;
    private final Object  instance;

    public BeanWrap(BeanDef def, Object instance) {
        this.def = def;
        this.instance = instance;
    }

    public BeanDef def()        { return def; }
    public Object  instance()   { return instance; }
    public boolean isSingleton(){ return def.scope() == Scope.SINGLETON; }
}