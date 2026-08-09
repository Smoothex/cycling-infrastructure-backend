package berlin.tu.cyclinginfrastructurebackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Configures the scheduler used by import and enrichment jobs. */
@Configuration
public class AsyncConfig {

    @Value("${pipeline.scheduler.thread-pool-size:6}")
    private int schedulerThreadPoolSize;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(schedulerThreadPoolSize);
        scheduler.setThreadNamePrefix("pipeline-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(60);
        scheduler.initialize();
        return scheduler;
    }
}
