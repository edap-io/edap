/*
 * Copyright 2023 The edap Project
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

package io.edap.container.transactional;

import io.edap.data.jdbc.JdbcBaseDao;
import io.edap.tx.EdapTransactionManager;
import io.edap.tx.TransactionContext;
import io.edap.tx.annotation.ManualTransaction;
import io.edap.tx.annotation.Transactional;
import io.edap.tx.exception.IllegalTransactionStateException;
import io.edap.tx.jdbc.DataSourceTransactionManager;
import io.edap.tx.propagation.Propagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4 端到端集成测试 —— {@code @ManualTransaction} + 容器自动按 DataSource 创建
 * {@code DataSourceTransactionManager} + transactionManager 按名路由。
 *
 * <p>覆盖场景:</p>
 * <ul>
 *   <li>单 DataSource 时 {@code @ManualTransaction} 不指定名 → 走默认 tm</li>
 *   <li>多 DataSource 时 {@code @ManualTransaction(transactionManager="...")} → 按名路由到指定 tm</li>
 *   <li>多 DataSource 时 {@code @ManualTransaction} 不指定名 → 启动失败(fail-fast)</li>
 *   <li>同名 ds 写的不同 tm 之间隔离 — 写到 audit DS 的数据用 main DS 查不到</li>
 *   <li>{@code @ManualTransaction} 内 {@link TransactionContext#current()} 可拿到 ctx</li>
 *   <li>业务方调 {@code ctx.commit()} / {@code ctx.rollback()} 控制生效</li>
 *   <li>业务方忘 commit 时,wrapper finally 兜底 rollback</li>
 * </ul>
 */
class ManualTransactionIntegrationTest {

    private DataSource mainDs;
    private DataSource auditDs;
    private Map<String, EdapTransactionManager> tms;

    @BeforeEach
    void setUp() throws Exception {
        // 两个独立 H2 DB(各自 t 表,各自数据)
        mainDs = TestSchemaSetup.create();
        auditDs = TestSchemaSetup.create();
        TransactionContext.unbind();

        // 模拟 AppContext 的 tm 注册表构建过程,产出 registry map
        tms = new LinkedHashMap<>();
        tms.put("transactionManager_main", new DataSourceTransactionManager(mainDs));
        tms.put("transactionManager_audit", new DataSourceTransactionManager(auditDs));
    }

    @AfterEach
    void tearDown() {
        TransactionContext.unbind();
        if (mainDs instanceof com.zaxxer.hikari.HikariDataSource) {
            ((com.zaxxer.hikari.HikariDataSource) mainDs).close();
        }
        if (auditDs instanceof com.zaxxer.hikari.HikariDataSource) {
            ((com.zaxxer.hikari.HikariDataSource) auditDs).close();
        }
    }

    // ============ Test 1:单 DataSource + @ManualTransaction 不指定名 → 默认 tm ============

    @Test
    @DisplayName("单 ds:@ManualTransaction 不指定 transactionManager → 默认 tm")
    void singleDs_usesDefaultTm() throws Exception {
        // 只注册 main 一个 tm,默认指向它
        Map<String, EdapTransactionManager> onlyMain = new HashMap<>();
        onlyMain.put("transactionManager_main", tms.get("transactionManager_main"));

        registerTms(onlyMain, null);
        AuditService target = new AuditServiceImpl(mainDs);
        AuditService proxy = wrap(AuditService.class, target);

        proxy.commitOk(101, "single-default");

        // 数据应写到 main ds
        assertEquals(1, TestSchemaSetup.countById(mainDs, 101),
                "默认 tm 走 main ds,数据应可见");
    }

    @Test
    @DisplayName("单 ds + 业务 rollback → wrapper finally 防御兜底:数据不入库")
    void singleDs_businessRollback_isFlushed() throws Exception {
        Map<String, EdapTransactionManager> onlyMain = new HashMap<>();
        onlyMain.put("transactionManager_main", tms.get("transactionManager_main"));

        registerTms(onlyMain, null);
        AuditService target = new AuditServiceImpl(mainDs);
        AuditService proxy = wrap(AuditService.class, target);

        proxy.rollbackOk(102, "single-rollback");

        // 业务方调 ctx.rollback() → 数据不入库
        assertEquals(0, TestSchemaSetup.countById(mainDs, 102),
                "业务方显式 rollback 应让数据不入库");
    }

    @Test
    @DisplayName("单 ds + 业务忘 commit + 没抛异常 → wrapper finally 兜底 rollback")
    void singleDs_businessForgetsClose_isAutoRolledBack() throws Exception {
        Map<String, EdapTransactionManager> onlyMain = new HashMap<>();
        onlyMain.put("transactionManager_main", tms.get("transactionManager_main"));

        registerTms(onlyMain, null);
        AuditService target = new AuditServiceImpl(mainDs);
        AuditService proxy = wrap(AuditService.class, target);

        proxy.forgetsToClose(103, "forgot-close");

        // 业务方忘 commit,正常路径退出(wrapper finally 检查 status.isCompleted() → false → 兜底 rollback)
        assertEquals(0, TestSchemaSetup.countById(mainDs, 103),
                "业务忘 commit → wrapper 兜底 rollback → 数据不应入库");
    }

    @Test
    @DisplayName("单 ds + 业务忘 commit + 抛异常 → wrapper catch 兜底 rollback")
    void singleDs_businessThrows_isAutoRolledBack() throws Exception {
        Map<String, EdapTransactionManager> onlyMain = new HashMap<>();
        onlyMain.put("transactionManager_main", tms.get("transactionManager_main"));

        registerTms(onlyMain, null);
        AuditService target = new AuditServiceImpl(mainDs);
        AuditService proxy = wrap(AuditService.class, target);

        assertThrows(RuntimeException.class, () -> proxy.throwsWithoutClosing(104, "threw"));
        assertEquals(0, TestSchemaSetup.countById(mainDs, 104),
                "业务抛异常 → wrapper catch 路径 rollback");
    }

    // ============ Test 2:多 DataSource + @ManualTransaction(transactionManager=...) → 按名路由 ============

    @Test
    @DisplayName("多 ds:@ManualTransaction(transactionManager='transactionManager_audit') → 写到 audit ds")
    void multiDs_namedRouterToAudit() throws Exception {
        // 默认指向 main(模拟 @Primary main)
        registerTms(tms, tms.get("transactionManager_main"));
        AuditService target = new AuditServiceImpl(auditDs);
        AuditService proxy = wrap(AuditService.class, target);

        // 用 audit ds bean 的 Service,但方法注解指向 transactionManager_audit
        // 实际上 AuditService 实现类构造时绑了一个 ds;这里校验 wrapper 用 audit tm 时,
        // 应该写到 auditDs。但 AuditServiceImpl.dao 用的是 impl.ctor 传入的 ds,
        // 所以这里改成显式路由到 audit tm 后,业务方法用 auditDao 而非 AuditService.dao。
        // 由于事务本身由 wrapper 管理,dao 走 TxConnectionHolder(自动拿当前 tx 连接),
        // 因此实际写哪个 ds 由 wrapper 开 tm 时 tm 绑定的 ds 决定。
        proxy.writeToAuditTm(200, "via-audit-tm");

        // 数据应写到 audit ds
        assertEquals(1, TestSchemaSetup.countById(auditDs, 200),
                "明确指 transactionManager_audit 的方法 → 数据写到 audit ds");
        // 不应写到 main ds
        assertEquals(0, TestSchemaSetup.countById(mainDs, 200),
                "audit tm 的事务不应写到 main ds");
    }

    @Test
    @DisplayName("多 ds:不指定 transactionManager + 无 @Primary 默认 → 启动期 fail-fast")
    void multiDs_noDefault_failsFast() throws Exception {
        // 多 tm 但 defaultFinal = null
        registerTms(tms, null);
        AuditService target = new AuditServiceImpl(mainDs);
        AuditService proxy = wrap(AuditService.class, target);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> proxy.commitOk(300, "should-fail"));
        assertTrue(ex.getMessage().contains("No default transaction manager"),
                "异常应明确说明 default tm 不可用");
    }

    @Test
    @DisplayName("多 ds:@ManualTransaction(transactionManager='unknown') → 路由时报错")
    void multiDs_unknownTmName_throwsAtRuntime() throws Exception {
        registerTms(tms, tms.get("transactionManager_main"));
        AuditService target = new AuditServiceImpl(mainDs);
        AuditService proxy = wrap(AuditService.class, target);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> proxy.writeToUnknownTm(301, "no-such-tm"));
        assertTrue(ex.getMessage().contains("No transaction manager named 'unknown'"),
                "异常应明确说明名字找不到");
    }

    @Test
    @DisplayName("@ManualTransaction 方法内 ctx.current() 拿得到 ctx,且 isActive() == true 在 commit 前")
    void manualContext_isBoundAndActiveInside() throws Exception {
        Map<String, EdapTransactionManager> onlyMain = new HashMap<>();
        onlyMain.put("transactionManager_main", tms.get("transactionManager_main"));

        registerTms(onlyMain, null);
        InspectService target = new InspectServiceImpl();
        InspectService proxy = wrap(InspectService.class, target);

        boolean[] sawActive = {false};
        proxy.peekContext(ctx -> sawActive[0] = ctx.isActive());

        assertTrue(sawActive[0], "在 @ManualTransaction 方法体内 ctx.current().isActive() == true");
    }

    @Test
    @DisplayName("@ManualTransaction 重复 commit 第二次抛 IllegalTransactionStateException")
    void manualContext_doubleCommit_throws() throws Exception {
        Map<String, EdapTransactionManager> onlyMain = new HashMap<>();
        onlyMain.put("transactionManager_main", tms.get("transactionManager_main"));

        registerTms(onlyMain, null);
        InspectService target = new InspectServiceImpl();
        InspectService proxy = wrap(InspectService.class, target);

        // 业务方连续 commit 两次 —— 第二次 ctx.isActive() == false → 抛 IllegalTransactionStateException
        assertThrows(IllegalTransactionStateException.class,
                () -> proxy.doubleCommit(ctx -> {
                    ctx.commit();
                    ctx.commit();   // 第二次:status.isCompleted() == true → ctx 保证 isActive 抛出
                }));
    }

    // ============ Test 4:@Transactional(transactionManager="...") 也支持按名路由(对齐 @ManualTransaction) ============

    @Test
    @DisplayName("@Transactional(transactionManager='transactionManager_audit') 也按名路由到 audit tm")
    void transactional_alsoHonorsTransactionManagerAttr() throws Exception {
        // 多 ds 时,@Transactional(transactionManager='transactionManager_audit') 走 audit tm。
        registerTms(tms, tms.get("transactionManager_main"));
        TransactionalAuditService target = new TransactionalAuditServiceImpl(auditDs);
        TransactionalAuditService proxy = wrap(TransactionalAuditService.class, target);

        // 关键:wrapper-managed commit/route 直接写到 audit ds
        proxy.writeViaTransactional(400, "tx-audit-ds");

        assertEquals(1, TestSchemaSetup.countById(auditDs, 400),
                "@Transactional(transactionManager='transactionManager_audit') 写到 audit ds");
        assertEquals(0, TestSchemaSetup.countById(mainDs, 400),
                "@Transactional(transactionManager='transactionManager_audit') 不写到 main ds");
    }

    @Test
    @DisplayName("@Transactional(transactionManager='unknown') 同样 fail-fast")
    void transactional_unknownTmName_fails() throws Exception {
        registerTms(tms, tms.get("transactionManager_main"));
        TransactionalAuditService target = new TransactionalAuditServiceImpl(mainDs);
        TransactionalAuditService proxy = wrap(TransactionalAuditService.class, target);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> proxy.writeToUnknown(401, "fail"));
        assertTrue(ex.getMessage().contains("No transaction manager named 'unknown'"),
                "@Transactional 走相同 TransactionManagers 注册,异常文案应一致");
    }

    // ============ Test 5:Phase 4 Plan A —— TxConnectionHolder 自动同步 ds ============

    /**
     * 业务方最初把 dao 绑到 mainDs,后续调一个用 auditTm 路由到 auditDs 的 @ManualTransaction 方法,
     * tx 路径里 TxConnectionHolder 会把 holder.dataSource 自动 sync 到 auditDs;tx 结束后再调
     * 无事务 dao.insert()(不再 setDataSource)→ fallback 应自然 fall 到 auditDs。
     */
    @Test
    @DisplayName("Plan A:dao 绑 mainDs,tx 路由 audit,tx 后无 setDataSource 的 insert 落到 audit")
    void planA_holderSyncsDataSourceOnTx_postTxFallsToAudit() throws Exception {
        registerTms(tms, tms.get("transactionManager_main"));

        // PlanAServiceImpl 持有一个 dao 单例,初始 setDataSource(mainDs)
        PlanAService target = new PlanAServiceImpl(mainDs);
        PlanAService proxy = wrap(PlanAService.class, target);

        // 一次调用里:先在 tx 路径里 insert,commit,然后再 insert(无 tx,无 setDataSource)
        proxy.txThenPost(600, "planA");

        // tx 路径 → audit ds(由 @ManualTransaction 路由到 transactionManager_audit)
        assertEquals(1, TestSchemaSetup.countById(auditDs, 600),
                "tx 路由到 audit → 第一行写 audit ds");
        // post-tx fallback → 因为 Plan A 自动 sync,fallback 也走 audit ds
        assertEquals(1, TestSchemaSetup.countById(auditDs, 601),
                "post-tx fallback 自动落 audit ds(Plan A 同步过 holder.dataSource)");
        // main ds 全程没参与
        assertEquals(0, TestSchemaSetup.countById(mainDs, 600),
                "main ds 不应有 tx 路径写的数据");
        assertEquals(0, TestSchemaSetup.countById(mainDs, 601),
                "main ds 不应有 post-tx fallback 写的数据");
    }

    /**
     * 业务方在 tx 内部"误导性"地调 dao.setDataSource(mainDs) —— 期望 tx 路径仍走
     * audit connection(因为 TxConnectionHolder.getConnection 在 tx 模式下直接返回
     * res.getConnection(),不读 holder.dataSource)。如果以后有人重构让 tx 路径
     * 读 holder.dataSource,这条测试会立刻 fail。
     */
    @Test
    @DisplayName("Plan A:tx 期间业务方调 setDataSource(mainDs) 不污染 tx 路径")
    void planA_setDataSourceDuringTx_doesNotPolluteTxPath() throws Exception {
        registerTms(tms, tms.get("transactionManager_main"));

        SetDataSourceDuringTxService target = new SetDataSourceDuringTxServiceImpl(mainDs, mainDs);
        SetDataSourceDuringTxService proxy = wrap(SetDataSourceDuringTxService.class, target);

        proxy.txWithMisleadingSetDataSource(700, "audit-only");

        assertEquals(1, TestSchemaSetup.countById(auditDs, 700),
                "tx 路径返回 res.getConnection(),不读 holder.dataSource,仍写 audit");
        assertEquals(0, TestSchemaSetup.countById(mainDs, 700),
                "setDataSource(mainDs) 在 tx 期间不应把 insert 路由到 main ds");
    }

    // ============ 工具 ============

    private interface ContextCallback {
        void run(TransactionContext ctx);
    }

    private <T> T wrap(Class<T> iface, T target) {
        TransactionalBeanPostProcessor bpp = new TransactionalBeanPostProcessor();
        return iface.cast(bpp.postProcessAfterInit(target, "test-bean"));
    }

    /**
     * 复刻 AppContext 中 {@code configureTransactionalInfrastructure} 的语义 —— 测试场景下
     * 不真启容器,手动调 {@link io.edap.tx.TransactionManagers#register(String, EdapTransactionManager)}
     * 注册。空名 → 走默认 defaultFinal;若 defaultFinal==null 且 byName 只有一项, 用那一项;
     * 否则不注册空名 → wrapper 调 {@code TransactionManagers.get("")} 时会 fail-fast
     * (让多 ds 无 @Primary 的场景正确报错)。
     */
    private void registerTms(Map<String, EdapTransactionManager> byName,
                             EdapTransactionManager defaultFinal) {
        EdapTransactionManager resolvedDefault = defaultFinal;
        if (resolvedDefault == null && byName.size() == 1) {
            resolvedDefault = byName.values().iterator().next();
        }
        for (Map.Entry<String, EdapTransactionManager> e : byName.entrySet()) {
            io.edap.tx.TransactionManagers.register(e.getKey(), e.getValue());
        }
        if (resolvedDefault != null) {
            io.edap.tx.TransactionManagers.register("", resolvedDefault);
        }
    }

    // ============ 测试桩 ============

    /**
     * 业务接口 —— 几种 @ManualTransaction 模式的字段。
     * 实际写库逻辑由 AuditServiceImpl 内部 dao 完成。数据源是什么 ds 由注解的 transactionManager 决定。
     */
    interface AuditService {
        // 场景 1:不指定 tm → 走默认
        @ManualTransaction
        void commitOk(int id, String v) throws SQLException;

        @ManualTransaction
        void rollbackOk(int id, String v) throws SQLException;

        @ManualTransaction
        void forgetsToClose(int id, String v) throws SQLException;

        @ManualTransaction
        void throwsWithoutClosing(int id, String v) throws SQLException;

        // 场景 2:指定 audit tm
        @ManualTransaction(transactionManager = "transactionManager_audit")
        void writeToAuditTm(int id, String v) throws SQLException;

        // 场景 3:指定不存在的 tm
        @ManualTransaction(transactionManager = "unknown")
        void writeToUnknownTm(int id, String v) throws SQLException;
    }

    static class AuditServiceImpl implements AuditService {
        private final DataSource ds;

        AuditServiceImpl(DataSource ds) { this.ds = ds; }

        @Override
        public void commitOk(int id, String v) throws SQLException {
            TransactionContext ctx = TransactionContext.current();
            AuditDao dao = new AuditDao();
            dao.setDataSource(ds);
            try {
                dao.insert(id, v);
                ctx.commit();
            } catch (Exception e) {
                ctx.rollback();
                throw e;
            }
        }

        @Override
        public void rollbackOk(int id, String v) throws SQLException {
            TransactionContext ctx = TransactionContext.current();
            AuditDao dao = new AuditDao();
            dao.setDataSource(ds);
            dao.insert(id, v);
            ctx.rollback();
        }

        @Override
        public void forgetsToClose(int id, String v) throws SQLException {
            // 模拟业务方:拿到 ctx 但忘 commit —— wrapper finally 兜底 rollback
            TransactionContext ctx = TransactionContext.current();
            // 验证 isActive
            assertTrue(ctx.isActive(), "刚 bind 完 ctx.isActive() == true");
            AuditDao dao = new AuditDao();
            dao.setDataSource(ds);
            dao.insert(id, v);
            // 故意不 commit / 不 rollback → wrapper finally 兜底
        }

        @Override
        public void throwsWithoutClosing(int id, String v) throws SQLException {
            // 业务方不仅忘 commit 还抛异常 —— wrapper catch 路径防御 rollback
            AuditDao dao = new AuditDao();
            dao.setDataSource(ds);
            dao.insert(id, v);
            throw new RuntimeException("simulated failure without commit");
        }

        @Override
        public void writeToAuditTm(int id, String v) throws SQLException {
            TransactionContext ctx = TransactionContext.current();
            AuditDao dao = new AuditDao();
            // 关键点:wrapper 用 transactionManager_audit 开的 tx,从 audit tm 拿到 connection
            // 后,TxConnectionHolder 自动从 ThreadLocal 拿到该 connection,所以 dao 里
            // setDataSource(auditDs) 必须配套,但更可靠的做法是 dao 也接到 auditDs。
            // 这里 setDataSource 直接给 auditDs,验证 wrapper 路由到 audit tm 是耦合的(测试用)。
            dao.setDataSource(ds);
            try {
                dao.insert(id, v);
                ctx.commit();
            } catch (Exception e) {
                ctx.rollback();
                throw e;
            }
        }

        @Override
        public void writeToUnknownTm(int id, String v) throws SQLException {
            // 写不到这里 —— wrapper 入口 TransactionManagers.get("unknown") 抛 IllegalStateException
        }
    }

    /** Inspect 接口 —— 验证 ctx 行为(无写库副作用)。 */
    interface InspectService {
        @ManualTransaction
        void peekContext(ContextCallback cb);

        @ManualTransaction
        void doubleCommit(ContextCallback cb);
    }

    /** Transactional 接口 —— @Transactional(transactionManager="...") 路由测试。 */
    interface TransactionalAuditService {
        @Transactional(transactionManager = "transactionManager_audit")
        void writeViaTransactional(int id, String v) throws SQLException;

        @Transactional(transactionManager = "unknown")
        void writeToUnknown(int id, String v) throws SQLException;
    }

    static class TransactionalAuditServiceImpl implements TransactionalAuditService {
        private final DataSource ds;

        TransactionalAuditServiceImpl(DataSource ds) { this.ds = ds; }

        @Override
        public void writeViaTransactional(int id, String v) throws SQLException {
            AuditDao dao = new AuditDao();
            dao.setDataSource(ds);
            dao.insert(id, v);
            // wrapper commit on exit
        }

        @Override
        public void writeToUnknown(int id, String v) throws SQLException {
            // 写不到这里 —— wrapper 入口 TransactionManagers.get("unknown") 抛 IllegalStateException
        }
    }

    static class InspectServiceImpl implements InspectService {
        @Override
        public void peekContext(ContextCallback cb) {
            TransactionContext ctx = TransactionContext.current();
            assertNotNull(ctx, "@ManualTransaction 方法体内 ctx != null");
            cb.run(ctx);
            ctx.commit();
        }

        @Override
        public void doubleCommit(ContextCallback cb) {
            TransactionContext ctx = TransactionContext.current();
            cb.run(ctx);
            // 不在这里 commit —— 让 callback 自己 commit 两次
        }
    }

    /**
     * Plan A 测试接口 —— dao 单例复用,验证 tx 路径 sync 后 post-tx fallback
     * 自动落 tx 用过的 ds。
     */
    interface PlanAService {
        @ManualTransaction(transactionManager = "transactionManager_audit")
        void txThenPost(int id, String v) throws SQLException;
    }

    /**
     * 关键设计:dao 单例(非每次新建),初始绑 mainDs。
     * tx 路径里 TxConnectionHolder.getConnection 走 ThreadLocal,顺带 Plan A 把
     * holder.dataSource 从 mainDs 同步到 auditDs;commit 后再 insert(无 setDataSource),
     * fallback 路径用同步过的 auditDs → 数据落到 audit ds。
     */
    static class PlanAServiceImpl implements PlanAService {
        private final AuditDao sharedDao;

        PlanAServiceImpl(DataSource initialDs) {
            this.sharedDao = new AuditDao();
            this.sharedDao.setDataSource(initialDs); // 业务方最初绑 mainDs
        }

        @Override
        public void txThenPost(int id, String v) throws SQLException {
            TransactionContext ctx = TransactionContext.current();
            // tx 路由到 audit:sharedDao 没重新 setDataSource,但 tx 路径走 ThreadLocal
            sharedDao.insert(id, v);
            ctx.commit();

            // tx 结束后再 insert,仍然不重新 setDataSource
            // —— 期望 fallback 走 Plan A 同步过的 auditDs
            sharedDao.insert(id + 1, v + "-post");
        }
    }

    /**
     * Plan A 第二个测试接口 —— 业务方在 tx 内部"误导性"地调 dao.setDataSource,
     * 验证 tx 路径不读 holder.dataSource,不会被误导。
     */
    interface SetDataSourceDuringTxService {
        @ManualTransaction(transactionManager = "transactionManager_audit")
        void txWithMisleadingSetDataSource(int id, String v) throws SQLException;
    }

    static class SetDataSourceDuringTxServiceImpl implements SetDataSourceDuringTxService {
        private final AuditDao sharedDao;
        private final DataSource mainDs;

        SetDataSourceDuringTxServiceImpl(DataSource initialDs, DataSource mainDs) {
            this.sharedDao = new AuditDao();
            this.sharedDao.setDataSource(initialDs);
            this.mainDs = mainDs;
        }

        @Override
        public void txWithMisleadingSetDataSource(int id, String v) throws SQLException {
            TransactionContext ctx = TransactionContext.current();
            // tx 路由到 audit(由 wrapper 的 @ManualTransaction(transactionManager=...) 保证)
            // 业务方误导性 setDataSource(mainDs) —— 期望 tx 路径仍返回 tx 的 audit connection
            sharedDao.setDataSource(mainDs);
            sharedDao.insert(id, v);
            ctx.commit();
        }
    }

    /** 简化 dao —— 走 TxConnectionHolder 自动拿 tx connection(Phase 3 SPI 已就绪)。 */
    static class AuditDao extends JdbcBaseDao {
        void insert(int id, String v) throws SQLException {
            try (Statement st = getStatementSession().getConHolder().getConnection().createStatement()) {
                st.executeUpdate("INSERT INTO t(id, v) VALUES (" + id + ", '" + v + "')");
            }
        }
    }
}
