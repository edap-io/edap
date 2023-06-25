package io.edap.data;

import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;

import static io.edap.data.util.DaoUtil.getViewDaoName;
import static io.edap.util.AsmUtil.toInternalName;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class JdbcViewDaoGenerator {

    private static String PARENT_NAME = toInternalName(JdbcBaseViewDao.class.getName());
    private static String VIEW_IFACT_NAME = toInternalName(JdbcViewDao.class.getName());
    private static String FIELD_SET_FUNC_NAME = toInternalName(JdbcFieldSetFunc.class.getName());
    private static String REGISTER_NAME = toInternalName(JdbcDaoRegister.class.getName());

    private Class<?> view;
    private String viewName;

    private String databaseType;

    private String daoName;

    private ClassWriter cw;

    public JdbcViewDaoGenerator(Class<?> view, String databaseType) {
        this.view = view;
        this.viewName = toInternalName(view.getName());
        this.databaseType = databaseType;
        this.daoName = toInternalName(getViewDaoName(view));
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = daoName;

        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        String daoDescriptor = "L" + PARENT_NAME + ";L" + VIEW_IFACT_NAME + "<L"
                + toInternalName(view.getName()) + ";>;";
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, daoName,
                daoDescriptor, PARENT_NAME, new String[]{VIEW_IFACT_NAME});

        visitInitMethod();
        visitClinitMethod();

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
