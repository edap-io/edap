/*
 * Copyright 2020 The edap Project
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

package io.edap.data;

import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.data.util.DaoUtil.getEntityDaoName;
import static io.edap.data.util.DaoUtil.getFieldSetFuncName;
import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;


public class JdbcDaoRegister {

    private final ReentrantLock lock = new ReentrantLock();

    private DaoLoader daoLoader;

    private JdbcDaoRegister() {
        daoLoader = new DaoLoader(this.getClass().getClassLoader());
    }

    public <T> FieldSetFunc<T> getFieldSetFunc(Class<T> entity, List<String> columns) {
        FieldSetFunc func = null;
        try {
            lock.lock();
            String name = getFieldSetFuncName(entity, columns);
            Class funcClazz;
            try {
                funcClazz = Class.forName(name);
            } catch (ClassNotFoundException e) {
                funcClazz = generateFieldSetFuncClass(entity, columns);
            }
            if (funcClazz != null) {
                try {
                    func = (FieldSetFunc) funcClazz.newInstance();
                } catch (InstantiationException | IllegalAccessException ex) {
                    throw new RuntimeException("generateFieldSetFunc "
                            + funcClazz.getName() + " error", ex);
                }
            }
            return func;
        } finally {
            lock.unlock();
        }
    }

    private Class generateFieldSetFuncClass(Class<?> entity, List<String> orignalColumns) {
        List<String> columns = new ArrayList<>();
        if (!CollectionUtils.isEmpty(orignalColumns)) {

            orignalColumns.forEach(c -> {
                columns.add(c.toLowerCase(Locale.ENGLISH));
            });
        }
        JdbcFieldSetFuncGenerator generator = new JdbcFieldSetFuncGenerator(entity, columns);
        String funcName = toLangName(getFieldSetFuncName(entity, columns));
        Class funcCls = null;
        try {
            funcCls = daoLoader.loadClass(funcName);
            if (funcCls != null) {
                return funcCls;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            GeneratorClassInfo gci = generator.getClassInfo();

            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            funcCls = daoLoader.define(toLangName(gci.clazzName), bs, 0, bs.length);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                if (daoLoader.loadClass(funcName) != null) {
                    return daoLoader.loadClass(funcName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateFieldSetFunc " + funcName + " error", ex);
            }
        }
        return funcCls;
    }

    public <T> JdbcEntityDao<T> getEntityDao(Class<T> entity, String databaseType) {
        JdbcEntityDao dao = null;
        try {
            lock.lock();
            String name = getEntityDaoName(entity);
            Class daoClazz;
            try {
                daoClazz = Class.forName(name);
            } catch (ClassNotFoundException e) {
                daoClazz = generateEntityDaoClass(entity, databaseType);
            }
            if (daoClazz != null) {
                try {
                    dao = (JdbcEntityDao) daoClazz.newInstance();
                } catch (InstantiationException | IllegalAccessException ex) {
                    throw new RuntimeException("generateEntityDao "
                            + daoClazz.getName() + " error", ex);
                }
            }
            return dao;
        } finally {
            lock.unlock();
        }
    }

    private Class generateEntityDaoClass(Class<?> entity, String databaseType) {
        JdbcEntityDaoGenerator generator = new JdbcEntityDaoGenerator(entity, databaseType);
        String daoName = toLangName(getEntityDaoName(entity));
        Class daoCls = null;
        try {
            GeneratorClassInfo gci = generator.getClassInfo();
            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            daoCls = daoLoader.define(toLangName(gci.clazzName), bs, 0, bs.length);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                if (daoLoader.loadClass(daoName) != null) {
                    return daoLoader.loadClass(daoName);
                }
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException("generateEntityDao " + daoName + " error", ex);
            }
        }
        return daoCls;
    }

    public static final JdbcDaoRegister instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final JdbcDaoRegister INSTANCE = new JdbcDaoRegister();
    }

    class DaoLoader extends ClassLoader {

        public DaoLoader(ClassLoader parent) {
            super(parent);
        }

        public Class define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}