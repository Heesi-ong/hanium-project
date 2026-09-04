package com.hanium.presentation.infrastructure.storage;

import com.hanium.presentation.global.exception.BusinessException;
import com.hanium.presentation.global.exception.ErrorCode;
import com.hanium.presentation.global.properties.MinioProperties;
import io.minio.MinioClient;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.DeleteResult;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MinioObjectStorage}의 예외 변환 경로를 검증합니다. 정상 라운드트립은
 * {@code MinioObjectStorageIntegrationTest}(실제 MinIO)가 담당하고, 여기서는 MinIO SDK가
 * 던지는 예외를 사용자용 {@link BusinessException}으로 어떻게 바꾸는지에 집중합니다.
 */
class MinioObjectStorageTest {

    private MinioClient minioClient;
    private MinioObjectStorage storage;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        storage = new MinioObjectStorage(
                minioClient,
                new MinioProperties(
                        "http://minio:9000",
                        "http://localhost:9000",
                        "access",
                        "secret",
                        "hanium-storage"
                )
        );
    }

    private ErrorResponseException errorResponseException(String code) throws Exception {
        ErrorResponseException exception = mock(ErrorResponseException.class);
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(exception.errorResponse()).thenReturn(errorResponse);
        when(errorResponse.code()).thenReturn(code);
        return exception;
    }

    private void assertBusiness(Throwable throwable, ErrorCode expected) {
        assertThat(throwable)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void putObjectFailureBecomesFileUploadFailed() throws Exception {
        when(minioClient.putObject(any())).thenThrow(new RuntimeException("connection reset"));

        assertBusiness(
                catchThrowable(() -> storage.putObject(
                        "uploads/j/v.mp4", new ByteArrayInputStream(new byte[] {1}), 1, "video/mp4")),
                ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    void getObjectMapsNoSuchKeyToFileNotFound() throws Exception {
        ErrorResponseException noSuchKey = errorResponseException("NoSuchKey");
        when(minioClient.getObject(any())).thenThrow(noSuchKey);

        assertBusiness(catchThrowable(() -> storage.getObject("uploads/j/v.mp4")), ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void getObjectMapsOtherErrorResponseToUploadFailed() throws Exception {
        ErrorResponseException accessDenied = errorResponseException("AccessDenied");
        when(minioClient.getObject(any())).thenThrow(accessDenied);

        assertBusiness(catchThrowable(() -> storage.getObject("uploads/j/v.mp4")), ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    void getObjectMapsGenericExceptionToUploadFailed() throws Exception {
        when(minioClient.getObject(any())).thenThrow(new RuntimeException("io"));

        assertBusiness(catchThrowable(() -> storage.getObject("uploads/j/v.mp4")), ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    void existsReturnsFalseWhenObjectIsMissing() throws Exception {
        ErrorResponseException noSuchKey = errorResponseException("NoSuchKey");
        when(minioClient.statObject(any())).thenThrow(noSuchKey);

        assertThat(storage.exists("uploads/j/v.mp4")).isFalse();
    }

    @Test
    void existsThrowsOnOtherErrorResponse() throws Exception {
        ErrorResponseException accessDenied = errorResponseException("AccessDenied");
        when(minioClient.statObject(any())).thenThrow(accessDenied);

        assertBusiness(catchThrowable(() -> storage.exists("uploads/j/v.mp4")), ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    void existsThrowsOnGenericException() throws Exception {
        when(minioClient.statObject(any())).thenThrow(new RuntimeException("timeout"));

        assertBusiness(catchThrowable(() -> storage.exists("uploads/j/v.mp4")), ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    void existsReturnsTrueWhenStatSucceeds() throws Exception {
        when(minioClient.statObject(any())).thenReturn(null);

        assertThat(storage.exists("uploads/j/v.mp4")).isTrue();
    }

    @Test
    void deleteObjectFailureBecomesFileDeleteFailed() throws Exception {
        doThrow(new RuntimeException("locked")).when(minioClient).removeObject(any());

        assertBusiness(catchThrowable(() -> storage.deleteObject("uploads/j/v.mp4")), ErrorCode.FILE_DELETE_FAILED);
    }

    @Test
    void deleteObjectsWithPrefixReturnsEarlyWhenNothingMatches() throws Exception {
        when(minioClient.listObjects(any())).thenReturn(List.of());

        storage.deleteObjectsWithPrefix("results/j/");

        verify(minioClient, never()).removeObjects(any(RemoveObjectsArgs.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteObjectsWithPrefixThrowsWhenAnyObjectDeletionFails() throws Exception {
        Result<Item> listed = mock(Result.class);
        Item item = mock(Item.class);
        when(item.objectName()).thenReturn("results/j/final-result.json");
        when(listed.get()).thenReturn(item);
        when(minioClient.listObjects(any())).thenReturn(List.of(listed));

        Result<DeleteResult.Error> errorResult = mock(Result.class);
        DeleteResult.Error error = mock(DeleteResult.Error.class);
        when(error.objectName()).thenReturn("results/j/final-result.json");
        when(error.message()).thenReturn("access denied");
        when(errorResult.get()).thenReturn(error);
        when(minioClient.removeObjects(any())).thenReturn(List.of(errorResult));

        assertBusiness(
                catchThrowable(() -> storage.deleteObjectsWithPrefix("results/j/")),
                ErrorCode.FILE_DELETE_FAILED);
    }

    @Test
    void deleteObjectsWithPrefixMapsListFailureToFileDeleteFailed() throws Exception {
        when(minioClient.listObjects(any())).thenThrow(new RuntimeException("list failed"));

        assertBusiness(
                catchThrowable(() -> storage.deleteObjectsWithPrefix("results/j/")),
                ErrorCode.FILE_DELETE_FAILED);
    }

    @Test
    void generatePresignedUrlFailureBecomesUploadFailed() throws Exception {
        when(minioClient.getPresignedObjectUrl(any())).thenThrow(new RuntimeException("clock skew"));

        assertBusiness(
                catchThrowable(() -> storage.generatePresignedUrl("uploads/j/v.mp4", Duration.ofMinutes(5))),
                ErrorCode.FILE_UPLOAD_FAILED);
    }

    @Test
    void generatePresignedUrlReturnsSdkValueOnSuccess() throws Exception {
        when(minioClient.getPresignedObjectUrl(any()))
                .thenReturn("http://minio:9000/hanium-storage/uploads/j/v.mp4?sig=x");

        assertThat(storage.generatePresignedUrl("uploads/j/v.mp4", Duration.ofMinutes(5)))
                .startsWith("http://minio:9000/");
    }
}
