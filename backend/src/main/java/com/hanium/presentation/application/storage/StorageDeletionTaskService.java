package com.hanium.presentation.application.storage;

import com.hanium.presentation.domain.storage.entity.StorageDeletionTask;
import com.hanium.presentation.domain.storage.entity.StorageDeletionEnqueueLock;
import com.hanium.presentation.domain.storage.repository.StorageDeletionEnqueueLockRepository;
import com.hanium.presentation.domain.storage.repository.StorageDeletionTaskRepository;
import com.hanium.presentation.domain.storage.type.StorageDeletionReason;
import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StorageDeletionTaskService {

    private final StorageDeletionTaskRepository storageDeletionTaskRepository;
    private final StorageDeletionEnqueueLockRepository storageDeletionEnqueueLockRepository;

    public StorageDeletionTaskService(
            StorageDeletionTaskRepository storageDeletionTaskRepository,
            StorageDeletionEnqueueLockRepository storageDeletionEnqueueLockRepository
    ) {
        this.storageDeletionTaskRepository = storageDeletionTaskRepository;
        this.storageDeletionEnqueueLockRepository = storageDeletionEnqueueLockRepository;
    }

    // 호출자에게 transaction이 있으면(예: ResultCommandService.deleteResult) 함께 커밋하고,
    // 스케줄러가 단독 호출하면 자체 transaction을 연다. REQUIRED 기본 전파를 사용하므로
    // 업무 데이터 삭제와 outbox 생성의 원자성은 유지된다.
    @Transactional
    public void enqueue(String jobId, String objectKeyPrefix, StorageDeletionReason reason) {
        String activeKey = StorageDeletionTask.buildActiveKey(objectKeyPrefix, reason);

        // 서로 다른 backend 인스턴스가 동시에 같은 orphan을 발견해도 check-then-insert가
        // 경쟁하지 않도록 전용 1행을 transaction 끝까지 잠근다. enqueue는 사용자 삭제나
        // 시간 단위 cleanup에서만 발생하므로 전역 직렬화 비용은 작고, MySQL/H2 양쪽에서
        // 동일한 SQL 계약을 유지할 수 있다.
        storageDeletionEnqueueLockRepository
                .findByIdForUpdate(StorageDeletionEnqueueLock.SINGLETON_ID)
                .orElseGet(() -> storageDeletionEnqueueLockRepository.saveAndFlush(
                        StorageDeletionEnqueueLock.singleton()
                ));
        if (storageDeletionTaskRepository.existsByActiveKey(activeKey)) {
            return;
        }

        storageDeletionTaskRepository.save(StorageDeletionTask.create(jobId, objectKeyPrefix, reason));
    }

    @Transactional
    public void requeueForManualRetry(Long taskId) {
        StorageDeletionTask task = storageDeletionTaskRepository.findById(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND, "삭제 작업을 찾을 수 없습니다. id=" + taskId));

        task.requeueForManualRetry();
    }
}
