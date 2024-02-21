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

package io.edap.data.jdbc;

import io.edap.data.jdbc.util.DaoUtil;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.util.AsmUtil.saveJavaFile;
import static io.edap.util.AsmUtil.toLangName;


public class JdbcDaoRegister {

    Logger LOG = LoggerManager.getLogger(JdbcDaoRegister.class);

    private final ReentrantLock lock = new ReentrantLock();

    private DaoLoader daoLoader;

    private JdbcDaoRegister() {
        daoLoader = new DaoLoader(this.getClass().getClassLoader());
    }

    public JdbcMapDao getMapDao() {
        return new JdbcBaseMapDao();
    }

    public <T> JdbcFieldSetFunc<T> getFieldSetFunc(Class<T> entity, List<String> columns) {
        return getFieldSetFunc(entity, columns, "");
    }

    public <T> JdbcFieldSetFunc<T> getFieldSetFunc(Class<T> entity, List<String> columns, String columnStr) {
        JdbcFieldSetFunc<T> func = null;
        Collections.sort(columns);
        try {
            lock.lock();
            String name = DaoUtil.getFieldSetFuncName(entity, columns, columnStr);
            Class funcClazz;
            try {
                funcClazz = daoLoader.loadClass(name);
            } catch (ClassNotFoundException e) {
                funcClazz = generateFieldSetFuncClass(entity, columns, columnStr);
            }
            if (funcClazz != null) {
                try {
                    func = (JdbcFieldSetFunc) funcClazz.newInstance();
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

    private Class<?> generateFieldSetFuncClass(Class<?> entity, List<String> orignalColumns, String columnStr) {
        List<String> columns = new ArrayList<>();
        if (!CollectionUtils.isEmpty(orignalColumns)) {

            orignalColumns.forEach(c -> {
                columns.add(c.toLowerCase(Locale.ENGLISH));
            });
        }
        JdbcFieldSetFuncGenerator generator = new JdbcFieldSetFuncGenerator(entity, columns, columnStr);
        String funcName = toLangName(DaoUtil.getFieldSetFuncName(entity, columns, columnStr));
        Class<?> funcCls = null;
        try {
            funcCls = daoLoader.loadClass(funcName);
            if (funcCls != null) {
                return funcCls;
            }
        } catch (Exception e) {
            LOG.info("daoLoader.loadClass error", e);
        }
        try {
            GeneratorClassInfo gci = generator.getClassInfo();

            byte[] bs = gci.clazzBytes;
            saveJavaFile("./" + gci.clazzName + ".class", bs);
            funcCls = daoLoader.define(toLangName(gci.clazzName), bs, 0, bs.length);
        } catch (Exception e) {
            LOG.info("daoLoader.loadClass error", e);
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

    public <T> JdbcEntityDao<T> getEntityDao(Class<T> entity, DaoOption daoOption) {
        JdbcEntityDao<T> dao = null;
        try {
            lock.lock();
            String name = DaoUtil.getEntityDaoName(entity);
            Class<?> daoClazz;
            try {
                daoClazz = daoLoader.loadClass(name);
            } catch (ClassNotFoundException e) {
                daoClazz = generateEntityDaoClass(entity, daoOption);
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

    private Class<?> generateEntityDaoClass(Class<?> entity, DaoOption daoOption) {
        JdbcEntityDaoGenerator generator = new JdbcEntityDaoGenerator(entity, daoOption);
        String daoName = toLangName(DaoUtil.getEntityDaoName(entity));
        Class<?> daoCls = null;
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

    public <T> JdbcViewDao<T> getViewDao(Class<T> entity, DaoOption daoOption) {
        JdbcViewDao<T> dao = null;
        try {
            lock.lock();
            String name = DaoUtil.getViewDaoName(entity);
            Class<?> daoClazz;
            try {
                daoClazz = daoLoader.loadClass(name);
            } catch (ClassNotFoundException e) {
                daoClazz = generateViewDaoClass(entity, daoOption);
            }
            if (daoClazz != null) {
                try {
                    dao = (JdbcViewDao) daoClazz.newInstance();
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

    private Class<?> generateViewDaoClass(Class<?> entity, DaoOption daoOption) {
        JdbcViewDaoGenerator generator = new JdbcViewDaoGenerator(entity, daoOption);
        String daoName = toLangName(DaoUtil.getViewDaoName(entity));
        Class<?> daoCls = null;
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

    public static JdbcDaoRegister instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final JdbcDaoRegister INSTANCE = new JdbcDaoRegister();
    }

    static class DaoLoader extends ClassLoader {

        public DaoLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> define(String className, byte[] bs, int offset, int len) {
            return super.defineClass(className, bs, offset, len);
        }
    }
}
