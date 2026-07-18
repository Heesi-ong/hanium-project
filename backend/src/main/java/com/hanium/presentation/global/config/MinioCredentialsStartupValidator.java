package com.hanium.presentation.global.config;

import com.hanium.presentation.global.properties.MinioProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

// application.yaml의 minio.access-key/secret-key는 로컬 편의를 위해 minioadmin/minioadmin123
// 기본값을 갖는다. docker-compose.yml은 MINIO_ROOT_USER/MINIO_ROOT_PASSWORD를 필수 변수로
// 강제하지만, docker-compose를 거치지 않는 배포 경로(예: 다른 오케스트레이터)에서는 이 기본값이
// 그대로 dev/prod에 들어갈 수 있다. JwtSecretStartupValidator와 같은 방식으로 기동 시점에
// 미리 걸러낸다.
@Component
@Profile({"dev", "prod"})
public class MinioCredentialsStartupValidator {

    private static final String INSECURE_DEFAULT_ACCESS_KEY = "minioadmin";
    private static final String INSECURE_DEFAULT_SECRET_KEY = "minioadmin123";

    private final String accessKey;
    private final String secretKey;

    public MinioCredentialsStartupValidator(MinioProperties minioProperties) {
        this.accessKey = minioProperties.accessKey();
        this.secretKey = minioProperties.secretKey();
    }

    @PostConstruct
    public void validate() {
        if (INSECURE_DEFAULT_ACCESS_KEY.equals(accessKey) || INSECURE_DEFAULT_SECRET_KEY.equals(secretKey)) {
            throw new IllegalStateException(
                    "dev/prod 환경에서는 MINIO_ACCESS_KEY/MINIO_SECRET_KEY(또는 MINIO_ROOT_USER/"
                            + "MINIO_ROOT_PASSWORD) 환경변수를 반드시 실제 강력한 값으로 설정해야 합니다. "
                            + "현재 값은 코드/문서에 공개된 placeholder 기본값(minioadmin/minioadmin123)입니다."
            );
        }
    }
}
