package io.github.jtconsole.migration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 启动期按 {@code PRAGMA user_version} 顺序执行未应用的迁移。
 *
 * <p>全新库与存量库走同一条路径：{@code schema.sql} 只负责建立历史基线表，新表、补列与种子数据
 * 一律由迁移拥有。全新库首次启动执行完全部迁移后版本号即为最新，重启不再重复执行。
 *
 * <p>需要迁移后结构才能工作的启动期组件 MUST 显式依赖本 bean（构造器注入），
 * {@code @DependsOnDatabaseInitialization} 只保证本 bean 晚于 {@code schema.sql}。
 */
@Component
@DependsOnDatabaseInitialization
public class SchemaMigrationRunner implements InitializingBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMigrationRunner.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final List<SchemaMigration> migrations;

    public SchemaMigrationRunner(
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            List<SchemaMigration> migrations) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.migrations = sortedAndValidated(migrations);
    }

    @Override
    public void afterPropertiesSet() {
        int current = currentVersion();
        List<SchemaMigration> pending = migrations.stream()
                .filter(migration -> migration.version() > current)
                .toList();
        if (pending.isEmpty()) {
            LOGGER.info("数据库结构已是最新版本 v{}", current);
            return;
        }

        LOGGER.info("数据库结构版本 v{}，待执行 {} 个迁移", current, pending.size());
        for (SchemaMigration migration : pending) {
            applyOne(migration);
        }
        LOGGER.info("数据库结构迁移完成，当前版本 v{}", currentVersion());
    }

    /** 迁移是否已全部执行完毕。供依赖迁移结果的启动期组件断言使用。 */
    public int currentVersion() {
        Integer version = jdbc.sql("PRAGMA user_version").query(Integer.class).single();
        return version == null ? 0 : version;
    }

    private void applyOne(SchemaMigration migration) {
        LOGGER.info("执行迁移 v{}：{}", migration.version(), migration.description());
        try {
            transactions.executeWithoutResult(status -> {
                migration.apply(jdbc);
                // PRAGMA 的取值不支持参数绑定；版本号来自代码常量且已校验为正整数，内联安全。
                jdbc.sql("PRAGMA user_version = " + migration.version()).update();
            });
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    "数据库迁移 v" + migration.version() + " 执行失败，已回滚且版本号未推进："
                            + migration.description(),
                    failure);
        }
    }

    private static List<SchemaMigration> sortedAndValidated(List<SchemaMigration> migrations) {
        List<SchemaMigration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(SchemaMigration::version));
        Set<Integer> seen = new HashSet<>();
        for (SchemaMigration migration : sorted) {
            if (migration.version() <= 0) {
                throw new IllegalStateException(
                        "迁移版本号必须为正整数：" + migration.getClass().getName());
            }
            if (!seen.add(migration.version())) {
                throw new IllegalStateException("迁移版本号重复：v" + migration.version());
            }
        }
        return List.copyOf(sorted);
    }
}
