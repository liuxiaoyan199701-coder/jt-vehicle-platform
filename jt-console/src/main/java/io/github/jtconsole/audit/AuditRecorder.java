package io.github.jtconsole.audit;

import io.github.jtconsole.config.ConsoleProperties;
import io.github.jtconsole.domain.AuditEntry;
import io.github.jtconsole.repository.AuditRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 审计落库：有界内存队列 + 单写线程批量提交。
 *
 * <p>SQLite 同一时刻只允许一个写事务，同步写审计会把延迟加到每一个业务写接口上；
 * 因此审计永远不参与业务事务，也永远不阻塞业务——队列满时丢弃并计数，
 * 业务请求照常成功。审计定位是运营追溯而不是法证留痕，秒级丢失窗口是可接受的取舍。
 */
@Component
public class AuditRecorder implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuditRecorder.class);
    private static final int BATCH_SIZE = 64;
    private static final long DRAIN_INTERVAL_MILLIS = 500;
    private static final long SHUTDOWN_GRACE_MILLIS = 3_000;

    private final AuditRepository repository;
    private final Clock clock;
    private final ArrayBlockingQueue<AuditEntry> queue;
    private final Thread writer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong writeFailures = new AtomicLong();

    @Autowired
    public AuditRecorder(AuditRepository repository, ConsoleProperties properties) {
        this(repository, properties.getAudit().getQueueCapacity(), Clock.systemUTC());
    }

    AuditRecorder(AuditRepository repository, int queueCapacity, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.writer = Thread.ofPlatform()
                .daemon(true)
                .name("audit-writer")
                .unstarted(this::drainLoop);
        this.writer.start();
    }

    /** 提交一条审计。永不抛异常、永不阻塞。 */
    public void record(AuditEntry entry) {
        if (!running.get()) {
            return;
        }
        if (!queue.offer(entry)) {
            long total = dropped.incrementAndGet();
            if (total == 1 || total % 100 == 0) {
                LOGGER.warn("审计队列已满，累计丢弃 {} 条", total);
            }
        }
    }

    public Stats stats() {
        return new Stats(queue.size(), written.get(), dropped.get(), writeFailures.get());
    }

    /** 分批删除超出保留期的记录，返回删除总数。清理放在低峰定时任务里调用。 */
    public long purgeOlderThan(Duration retention, int batchSize, int maxBatches) {
        String cutoff = clock.instant().minus(retention).toString();
        long total = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            int removed = repository.deleteOlderThan(cutoff, batchSize);
            total += removed;
            if (removed < batchSize) {
                break;
            }
        }
        if (total > 0) {
            LOGGER.info("按保留期清理审计日志 {} 条", total);
        }
        return total;
    }

    private void drainLoop() {
        List<AuditEntry> batch = new ArrayList<>(BATCH_SIZE);
        while (running.get() || !queue.isEmpty()) {
            try {
                AuditEntry first = queue.poll(DRAIN_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                flush(batch);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                batch.clear();
            }
        }
        if (!queue.isEmpty()) {
            List<AuditEntry> remaining = new ArrayList<>();
            queue.drainTo(remaining);
            flush(remaining);
        }
    }

    private void flush(List<AuditEntry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            repository.insertBatch(List.copyOf(batch));
            written.addAndGet(batch.size());
        } catch (RuntimeException failure) {
            // 审计写失败不能反噬业务，只记类别不记内容。
            writeFailures.addAndGet(batch.size());
            LOGGER.warn("审计批量写入失败（{} 条）：{}",
                    batch.size(), failure.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        writer.join(SHUTDOWN_GRACE_MILLIS);
    }

    /** 队列与写入统计，暴露到诊断接口以便发现审计积压。 */
    public record Stats(int queued, long written, long dropped, long writeFailures) {}

    Instant now() {
        return clock.instant();
    }
}
