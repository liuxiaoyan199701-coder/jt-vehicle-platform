package io.github.jtconsole.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对话与简报共用的工作线程池。
 *
 * <p>一次对话独占一个线程，从头阻塞到尾——控制台是 Spring MVC 没有响应式栈，与其为一个接口引入
 * 整套响应式服务器，不如用线程数直接给并发封顶。
 *
 * <p>队列容量为 0（同步移交）：满了立刻拒绝，由控制器回一句「当前对话较多，请稍后再试」。
 * 排队只会让请求在队列里坐到超时，用户对着转圈等更久，最后仍然失败。
 */
@Configuration(proxyBeanMethods = false)
public class AiExecutorConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService aiExecutor(ConsoleProperties properties) {
        int size = Math.max(1, properties.getAi().getConcurrentChats());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                size, size, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1),
                runnable -> Thread.ofPlatform().name("ai-worker-", 0).unstarted(runnable),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
