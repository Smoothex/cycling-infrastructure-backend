package berlin.tu.cyclinginfrastructurebackend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineWorkClaimServiceTest {

    @Test
    void startupRecoveryCommitsBeforeScheduledEnrichmentCanClaimWork() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(StartupConfiguration.class)) {
            ScheduledProbe probe = context.getBean(ScheduledProbe.class);
            assertThat(probe.started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.recoveryCommittedWhenStarted).isTrue();
            verify(context.getBean(EntityManager.class), times(8)).createNativeQuery(anyString());
        }
    }

    @Test
    void startupRecoveryClearsTheWeatherBatchIdentifier() {
        EntityManager entityManager = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.executeUpdate()).thenReturn(0);
        PipelineWorkClaimService service = new PipelineWorkClaimService(entityManager);

        service.resetInterruptedWork();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(8)).createNativeQuery(sql.capture());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("weather_processing_status = :pending")
                .contains("weather_processing_batch_id = NULL")
                .contains("WHERE weather_processing_status = :processing"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableTransactionManagement
    static class StartupConfiguration {
        @Bean
        EntityManager entityManager() {
            EntityManager entityManager = mock(EntityManager.class);
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(query.setParameter(anyString(), any())).thenReturn(query);
            when(query.executeUpdate()).thenReturn(0);
            return entityManager;
        }

        @Bean
        PipelineWorkClaimService claimService(EntityManager entityManager) {
            return new PipelineWorkClaimService(entityManager);
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }

        @Bean
        ScheduledProbe scheduledProbe(RecordingTransactionManager transactionManager) {
            return new ScheduledProbe(transactionManager);
        }
    }

    static class ScheduledProbe {
        final CountDownLatch started = new CountDownLatch(1);
        final RecordingTransactionManager transactionManager;
        volatile boolean recoveryCommittedWhenStarted;

        ScheduledProbe(RecordingTransactionManager transactionManager) {
            this.transactionManager = transactionManager;
        }

        @Scheduled(fixedDelay = 60_000)
        public void claimWork() {
            recoveryCommittedWhenStarted = transactionManager.committed.get();
            started.countDown();
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicBoolean committed = new AtomicBoolean();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed.set(true);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
