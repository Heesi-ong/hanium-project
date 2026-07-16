package com.hanium.presentation.infrastructure.storage;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteRequest;
import io.minio.messages.DeleteResult;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioObjectStorage(MinioClient minioClient, MinioProperties minioProperties) {
        this.minioClient = minioClient;
        this.bucketName = minioProperties.bucketName();
    }

    @Override
    public void putObject(String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .stream(inputStream, size, -1L)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception exception) {
            log.error("MINIO_PUT_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "오브젝트 저장에 실패했습니다. key=" + objectKey);
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (ErrorResponseException exception) {
            if (NO_SUCH_KEY.equals(exception.errorResponse().code())) {
                throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
            }
            log.error("MINIO_GET_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "오브젝트 조회에 실패했습니다. key=" + objectKey);
        } catch (Exception exception) {
            log.error("MINIO_GET_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "오브젝트 조회에 실패했습니다. key=" + objectKey);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (ErrorResponseException exception) {
            if (NO_SUCH_KEY.equals(exception.errorResponse().code())) {
                return false;
            }
            log.error("MINIO_STAT_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "오브젝트 상태 확인에 실패했습니다. key=" + objectKey);
        } catch (Exception exception) {
            log.error("MINIO_STAT_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "오브젝트 상태 확인에 실패했습니다. key=" + objectKey);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .build()
            );
        } catch (Exception exception) {
            log.error("MINIO_DELETE_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_DELETE_FAILED, "오브젝트 삭제에 실패했습니다. key=" + objectKey);
        }
    }

    @Override
    public void deleteObjectsWithPrefix(String prefix) {
        try {
            List<DeleteRequest.Object> objectsToDelete = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .prefix(prefix)
                            .recursive(true)
                            .build()
            );

            for (Result<Item> result : results) {
                Item item = result.get();
                objectsToDelete.add(new DeleteRequest.Object(item.objectName()));
            }

            if (objectsToDelete.isEmpty()) {
                return;
            }

            Iterable<Result<DeleteResult.Error>> deleteResults = minioClient.removeObjects(
                    RemoveObjectsArgs.builder()
                            .bucket(bucketName)
                            .objects(objectsToDelete)
                            .build()
            );

            for (Result<DeleteResult.Error> deleteResult : deleteResults) {
                DeleteResult.Error error = deleteResult.get();
                log.warn(
                        "MINIO_DELETE_PREFIX_PARTIAL_FAILURE prefix={} object={} message={}",
                        prefix,
                        error.objectName(),
                        error.message()
                );
            }
        } catch (Exception exception) {
            log.error("MINIO_DELETE_PREFIX_FAILED prefix={}", prefix, exception);
            throw new BusinessException(ErrorCode.FILE_DELETE_FAILED, "오브젝트 일괄 삭제에 실패했습니다. prefix=" + prefix);
        }
    }

    @Override
    public String generatePresignedUrl(String objectKey, Duration expiry) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .bucket(bucketName)
                            .object(objectKey)
                            .method(Http.Method.GET)
                            .expiry((int) expiry.toSeconds())
                            .build()
            );
        } catch (Exception exception) {
            log.error("MINIO_PRESIGNED_URL_FAILED objectKey={}", objectKey, exception);
            throw new BusinessException(ErrorCode.FILE_UPLOAD_FAILED, "다운로드 URL 생성에 실패했습니다. key=" + objectKey);
        }
    }
}
