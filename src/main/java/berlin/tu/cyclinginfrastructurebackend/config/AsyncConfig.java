package berlin.tu.cyclinginfrastructurebackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Configures a dedicated thread pool for detour analysis work.
 * Sized to match available CPU cores since the bottleneck is
 * CPU-bound GraphHopper routing, not I/O.
 */
@Configuration
public class AsyncConfig {

    @Value("${analysis.batch.thread-pool-size:8}")
    private int threadPoolSize;

    @Bean(name = "analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threadPoolSize);
        executor.setMaxPoolSize(threadPoolSize);
        executor.setQueueCapacity(1000);    // backpressure - one batch worth of work at a time
        executor.setThreadNamePrefix("detour-analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }
}

