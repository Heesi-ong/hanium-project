package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.repository.StorageDeletionTaskRepository;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.domain.storage.type.StorageDeletionTaskStatus;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.infrastructure.storage.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@TestPropertySource(properties = {
        "storage.deletion.max-attempts=3",
        "storage.deletion.base-backoff-minutes=2",
        "storage.deletion.max-backoff-minutes=240"
})
class StorageDeletionOutboxWorkerTest {

    @Autowired
    private StorageDeletionOutboxWorker storageDeletionOutboxWorker;

    @Autowired
    private StorageDeletionTaskRepository storageDeletionTaskRepository;

    @Autowired
    private StorageDeletionTaskService storageDeletionTaskService;

    @MockitoBean
    private ObjectStorage objectStorage;

    @MockitoBean
    private SchedulerDistributedLock schedulerDistributedLock;

    @BeforeEach
    void setUp() {
        storageDeletionTaskRepository.deleteAll();
        when(schedulerDistributedLock.tryLock(eq("storage-deletion-worker"), any(Duration.class))).thenReturn(true);
        when(schedulerDistributedLock.tryLock(eq("storage-deletion-retention"), any(Duration.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        storageDeletionTaskRepository.deleteAll();
    }

    @Test
    void marksTaskCompletedWhenDeletionSucceeds() {
        StorageDeletionTask newTask = StorageDeletionTask.create(
                "20260723100000-aaaaaaaa",
                "uploads/20260723100000-aaaaaaaa/",
                StorageDeletionReason.RESULT_DELETE
        );
        // 엔티티 생성 시점의 now()와 워커의 now() 체크 사이 미세한 시간차/정밀도 차이로
        // "이미 지난 시각"이 아닌 것으로 판정될 수 있으므로, 확실히 과거로 못박아 둔다.
        ReflectionTestUtils.setField(newTask, "nextAttemptAt", LocalDateTime.now().minusMinutes(1));
        StorageDeletionTask task = storageDeletionTaskRepository.save(newTask);
        doNothing().when(objectStorage).deleteObjectsWithPrefix("uploads/20260723100000-aaaaaaaa/");

        storageDeletionOutboxWorker.processPendingDeletions();

        StorageDeletionTask reloaded = storageDeletionTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StorageDeletionTaskStatus.COMPLETED);
        assertThat(reloaded.getCompletedAt()).isNotNull();
        verify(objectStorage).deleteObjectsWithPrefix("uploads/20260723100000-aaaaaaaa/");
    }

    @Test
    void schedulesRetryWithBackoffWhenDeletionFailsBelowMaxAttempts() {
        StorageDeletionTask newTask = StorageDeletionTask.create(
                "20260723100100-bbbbbbbb",
                "uploads/20260723100100-bbbbbbbb/",
                StorageDeletionReason.RESULT_DELETE
        );
        ReflectionTestUtils.setField(newTask, "nextAttemptAt", LocalDateTime.now().minusMinutes(1));
        StorageDeletionTask task = storageDeletionTaskRepository.save(newTask);
        doThrow(new RuntimeException("minio down"))
                .when(objectStorage).deleteObjectsWithPrefix("uploads/20260723100100-bbbbbbbb/");

        storageDeletionOutboxWorker.processPendingDeletions();

        StorageDeletionTask reloaded = storageDeletionTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StorageDeletionTaskStatus.PENDING);
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(reloaded.getLastError()).contains("minio down");
        // base-backoff-minutes=2, attemptCount=1 → 2 * 2^1 = 4분 뒤
        assertThat(reloaded.getNextAttemptAt()).isAfter(LocalDateTime.now().plusMinutes(3));
    }

    @Test
    void marksTaskDeadLetterAfterExceedingMaxAttempts() {
        StorageDeletionTask task = StorageDeletionTask.create(
                "20260723100200-cccccccc",
                "uploads/20260723100200-cccccccc/",
                StorageDeletionReason.RESULT_DELETE
        );
        // max-attempts=3이므로, 이미 2번 실패한 상태에서 한 번 더 실패하면 DEAD_LETTER가 된다.
        ReflectionTestUtils.setField(task, "attemptCount", 2);
        ReflectionTestUtils.setField(task, "nextAttemptAt", LocalDateTime.now().minusMinutes(1));
        StorageDeletionTask saved = storageDeletionTaskRepository.save(task);
        doThrow(new RuntimeException("minio down"))
                .when(objectStorage).deleteObjectsWithPrefix("uploads/20260723100200-cccccccc/");

        storageDeletionOutboxWorker.processPendingDeletions();

        StorageDeletionTask reloaded = storageDeletionTaskRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(StorageDeletionTaskStatus.DEAD_LETTER);
        assertThat(reloaded.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void doesNotProcessTasksScheduledInTheFuture() {
        StorageDeletionTask task = StorageDeletionTask.create(
                "20260723100300-dddddddd",
                "uploads/20260723100300-dddddddd/",
                StorageDeletionReason.RESULT_DELETE
        );
        ReflectionTestUtils.setField(task, "nextAttemptAt", LocalDateTime.now().plusHours(1));
        storageDeletionTaskRepository.save(task);

        storageDeletionOutboxWorker.processPendingDeletions();

        verify(objectStorage, never()).deleteObjectsWithPrefix(any());
    }

    @Test
    void skipsProcessingWhenDistributedLockIsAlreadyHeld() {
        storageDeletionTaskRepository.save(StorageDeletionTask.create(
                "20260723100400-eeeeeeee",
                "uploads/20260723100400-eeeeeeee/",
                StorageDeletionReason.RESULT_DELETE
        ));
        when(schedulerDistributedLock.tryLock(eq("storage-deletion-worker"), any(Duration.class))).thenReturn(false);

        storageDeletionOutboxWorker.processPendingDeletions();

        verify(objectStorage, never()).deleteObjectsWithPrefix(any());
    }

    @Test
    void doesNotCreateDuplicateActiveTaskForSameReasonAndPrefix() {
        storageDeletionTaskService.enqueue(
                "20260723100500-ffffffff",
                "uploads/20260723100500-ffffffff/",
                StorageDeletionReason.ORPHAN_CLEANUP
        );
        storageDeletionTaskService.enqueue(
                "20260723100500-ffffffff",
                "uploads/20260723100500-ffffffff/",
                StorageDeletionReason.ORPHAN_CLEANUP
        );

        assertThat(storageDeletionTaskRepository.findAll()).hasSize(1);
    }

    @Test
    void performsObjectStorageIoOutsideDatabaseTransaction() {
        StorageDeletionTask task = StorageDeletionTask.create(
                "20260723100600-11111111",
                "uploads/20260723100600-11111111/",
                StorageDeletionReason.RESULT_DELETE
        );
        ReflectionTestUtils.setField(task, "nextAttemptAt", LocalDateTime.now().minusMinutes(1));
        storageDeletionTaskRepository.save(task);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(objectStorage).deleteObjectsWithPrefix("uploads/20260723100600-11111111/");

        storageDeletionOutboxWorker.processPendingDeletions();

        assertThat(storageDeletionTaskRepository.findAll().get(0).getStatus())
                .isEqualTo(StorageDeletionTaskStatus.COMPLETED);
    }

    @Test
    void retriesTaskAfterAbandonedClaimLeaseExpires() {
        StorageDeletionTask task = StorageDeletionTask.create(
                "20260723100700-22222222",
                "uploads/20260723100700-22222222/",
                StorageDeletionReason.RESULT_DELETE
        );
        task.claim("abandoned-token", LocalDateTime.now().minusMinutes(1));
        storageDeletionTaskRepository.save(task);

        storageDeletionOutboxWorker.processPendingDeletions();

        StorageDeletionTask reloaded = storageDeletionTaskRepository.findAll().get(0);
        assertThat(reloaded.getStatus()).isEqualTo(StorageDeletionTaskStatus.COMPLETED);
        assertThat(reloaded.getProcessingToken()).isNull();
    }

    @Test
    void purgesOnlyCompletedTasksOlderThanRetentionPeriod() {
        StorageDeletionTask oldCompleted = StorageDeletionTask.create(
                "20260723100800-33333333",
                "uploads/20260723100800-33333333/",
                StorageDeletionReason.RESULT_DELETE
        );
        oldCompleted.markCompleted();
        ReflectionTestUtils.setField(oldCompleted, "completedAt", LocalDateTime.now().minusDays(31));

        StorageDeletionTask recentCompleted = StorageDeletionTask.create(
                "20260723100900-44444444",
                "uploads/20260723100900-44444444/",
                StorageDeletionReason.RESULT_DELETE
        );
        recentCompleted.markCompleted();
        ReflectionTestUtils.setField(recentCompleted, "completedAt", LocalDateTime.now().minusDays(5));

        StorageDeletionTask deadLetter = StorageDeletionTask.create(
                "20260723101000-55555555",
                "uploads/20260723101000-55555555/",
                StorageDeletionReason.RESULT_DELETE
        );
        deadLetter.markFailedAndScheduleRetry("failed", 1, 2, 240);
        ReflectionTestUtils.setField(deadLetter, "completedAt", LocalDateTime.now().minusDays(31));

        storageDeletionTaskRepository.saveAll(java.util.List.of(oldCompleted, recentCompleted, deadLetter));

        storageDeletionOutboxWorker.purgeCompletedTasks();

        assertThat(storageDeletionTaskRepository.findAll())
                .extracting(StorageDeletionTask::getJobId)
                .containsExactlyInAnyOrder(
                        "20260723100900-44444444",
                        "20260723101000-55555555"
                );
    }
}
