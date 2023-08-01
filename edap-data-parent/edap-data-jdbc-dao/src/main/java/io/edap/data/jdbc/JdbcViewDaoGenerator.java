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
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;

import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class JdbcViewDaoGenerator extends BaseDaoGenerator {
    private static String VIEW_IFACT_NAME = toInternalName(JdbcViewDao.class.getName());


    public JdbcViewDaoGenerator(Class<?> view, DaoOption daoOption) {
        this.entity = view;
        this.entityName = toInternalName(view.getName());
        this.daoOption = daoOption;
        this.daoName = toInternalName(DaoUtil.getViewDaoName(view));
        this.PARENT_NAME = toInternalName(JdbcBaseViewDao.class.getName());
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = daoName;

        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        String daoDescriptor = "L" + PARENT_NAME + ";L" + VIEW_IFACT_NAME + "<L"
                + toInternalName(entity.getName()) + ";>;";
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, daoName,
                daoDescriptor, PARENT_NAME, new String[]{VIEW_IFACT_NAME});

        visitInitMethod();
        visitClinitMethod();

        visitFillSqlFieldMethod();

        visitQueryOneParamMethod();
        visitGetSqlFieldSetFuncMethod();
        visitQueryTwoParamMethod();
        visitQueryObjectArrayMethod();

        visitFindOneOneParamMethod();
        visitFindOneTwoParamMethod();
        visitFindOneObjectArrayMethod();
        visitFindOneObjectArrayBridgeMethod();

        visitFindOneOneParamBridgeMethod();
        visitFindOneTwoParamBridgeMethod();

        visitFindByIdBridgeMethod();
        visitFindByIdMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();

        return gci;
    }
}
