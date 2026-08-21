package io.github.jtconsole.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jtconsole.support.TestSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class AiConversationRepositoryTest {

    private static final String OLD = "2025-01-01T00:00:00.000+08:00";
    private static final String CUTOFF = "2026-01-01T00:00:00.000+08:00";
    private static final String RECENT = "2026-02-01T00:00:00.000+08:00";

    private JdbcClient jdbc;
    private AiConversationRepository conversations;

    @BeforeEach
    void createDatabase() throws IOException, SQLException {
        Path database = Files.createTempFile("jt-console-ai-conversation-", ".db");
        database.toFile().deleteOnExit();
        SQLiteDataSource sqlite = new SQLiteDataSource();
        sqlite.setUrl("jdbc:sqlite:" + database.toAbsolutePath().toString().replace('\\', '/'));
        DataSource dataSource = sqlite;
        jdbc = JdbcClient.create(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        TestSchema.migrate(jdbc, transactions);
        conversations = new AiConversationRepository(jdbc);
    }

    @Test
    void deletesExpiredConversationAndItsMessagesButKeepsRecentConversation() {
        long expired = conversation("过期", OLD);
        long recent = conversation("未过期", RECENT);
        message(expired, "旧消息");
        message(recent, "新消息");

        int removed = conversations.deleteOlderThan(CUTOFF, 500);

        assertThat(removed).isEqualTo(1);
        assertThat(conversationIds()).containsExactly(recent);
        assertThat(messageConversationIds()).containsExactly(recent);
    }

    @Test
    void oneBatchNeverDeletesMoreThanConfiguredLimit() {
        long oldest = conversation("最旧", "2024-01-01T00:00:00.000+08:00");
        long second = conversation("次旧", "2024-02-01T00:00:00.000+08:00");
        long third = conversation("第三", "2024-03-01T00:00:00.000+08:00");
        message(oldest, "1");
        message(second, "2");
        message(third, "3");

        int removed = conversations.deleteOlderThan(CUTOFF, 2);

        assertThat(removed).isEqualTo(2);
        assertThat(conversationIds()).containsExactly(third);
        assertThat(messageConversationIds()).containsExactly(third);
    }

    @Test
    void emptyDatabaseIsANoOp() {
        assertThat(conversations.deleteOlderThan(CUTOFF, 500)).isZero();
        assertThat(conversationIds()).isEmpty();
        assertThat(messageConversationIds()).isEmpty();
    }

    @Test
    void removesOrphanMessagesInConfiguredBatches() {
        jdbc.sql("""
                        INSERT INTO ai_message
                            (conversation_id, role, content, prompt_tokens, completion_tokens, created_at)
                        VALUES (999, 'user', 'orphan-1', 0, 0, ?),
                               (998, 'user', 'orphan-2', 0, 0, ?),
                               (997, 'user', 'orphan-3', 0, 0, ?)
                        """)
                .param(OLD).param(OLD).param(OLD).update();

        assertThat(conversations.deleteOrphanMessages(2)).isEqualTo(2);
        assertThat(count("ai_message")).isEqualTo(1);
        assertThat(conversations.deleteOrphanMessages(2)).isEqualTo(1);
        assertThat(count("ai_message")).isZero();
    }

    @Test
    void usageRecordsAreNotDeletedWithConversationHistory() {
        long expired = conversation("计量保留", OLD);
        message(expired, "会被清理");
        jdbc.sql("""
                        INSERT INTO ai_usage
                            (tenant_id, account_id, kind, model, rounds,
                             prompt_tokens, completion_tokens, month, created_at)
                        VALUES (1, 7, 'chat', 'test', 1, 10, 5, '2025-01', ?)
                        """)
                .param(OLD)
                .update();

        conversations.deleteOlderThan(CUTOFF, 500);

        assertThat(count("ai_conversation")).isZero();
        assertThat(count("ai_message")).isZero();
        assertThat(count("ai_usage")).isEqualTo(1);
    }

    @Test
    void rejectsNonPositiveBatchSize() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> conversations.deleteOlderThan(CUTOFF, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    private long conversation(String title, String updatedAt) {
        long id = conversations.create(1L, 7L, title);
        jdbc.sql("UPDATE ai_conversation SET created_at = ?, updated_at = ? WHERE id = ?")
                .param(updatedAt).param(updatedAt).param(id).update();
        return id;
    }

    private void message(long conversationId, String content) {
        jdbc.sql("""
                        INSERT INTO ai_message
                            (conversation_id, role, content, prompt_tokens, completion_tokens, created_at)
                        VALUES (?, 'user', ?, 0, 0, ?)
                        """)
                .param(conversationId).param(content).param(OLD).update();
    }

    private java.util.List<Long> conversationIds() {
        return jdbc.sql("SELECT id FROM ai_conversation ORDER BY id")
                .query(Long.class).list();
    }

    private java.util.List<Long> messageConversationIds() {
        return jdbc.sql("SELECT conversation_id FROM ai_message ORDER BY id")
                .query(Long.class).list();
    }

    private int count(String table) {
        return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single();
    }
}
