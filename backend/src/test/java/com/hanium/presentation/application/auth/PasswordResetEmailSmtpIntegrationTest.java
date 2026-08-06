package com.hanium.presentation.application.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hanium.presentation.domain.admin.repository.AdminAuditLogRepository;
import com.hanium.presentation.domain.user.entity.PasswordResetEmailTask;
import com.hanium.presentation.domain.user.entity.PasswordResetToken;
import com.hanium.presentation.domain.user.entity.User;
import com.hanium.presentation.domain.user.repository.PasswordResetEmailTaskRepository;
import com.hanium.presentation.domain.user.repository.PasswordResetTokenRepository;
import com.hanium.presentation.domain.user.repository.UserRepository;
import com.hanium.presentation.domain.user.type.PasswordResetEmailTaskStatus;
import com.hanium.presentation.domain.user.type.UserRole;
import com.hanium.presentation.global.config.SchedulerDistributedLock;
import com.hanium.presentation.global.config.UserRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "admin.emails=admin@example.com",
        "password-reset.outbox.max-attempts=2",
        "password-reset.outbox.base-backoff-seconds=1",
        "password-reset.outbox.max-backoff-seconds=2",
        "password-reset.outbox.claim-lease-seconds=5",
        "scheduler.lock.password-reset-email-worker-ttl-seconds=4",
        "password-reset.outbox.poll-interval-ms=3600000",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.connectiontimeout=1000",
        "spring.mail.properties.mail.smtp.timeout=1000",
        "spring.mail.properties.mail.smtp.writetimeout=1000"
})
class PasswordResetEmailSmtpIntegrationTest {

    private static final LoopbackSmtpServer SMTP_SERVER = LoopbackSmtpServer.start();

    @DynamicPropertySource
    static void registerSmtpProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", SMTP_SERVER::port);
    }

    @Autowired
    private PasswordResetEmailOutboxWorker worker;

    @Autowired
    private PasswordResetEmailTaskService taskService;

    @Autowired
    private PasswordResetEmailTaskRepository taskRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    @Autowired
    private UserRateLimiter userRateLimiter;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SchedulerDistributedLock distributedLock;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        taskRepository.deleteAll();
        tokenRepository.deleteAll();
        userRepository.deleteAll();
        userRateLimiter.resetForTest();
        SMTP_SERVER.acceptMessages();
        SMTP_SERVER.clearMessages();
        when(distributedLock.tryLock(eq("password-reset-email-worker"), any(Duration.class)))
                .thenReturn(true);
    }

    @AfterAll
    static void stopSmtpServer() {
        SMTP_SERVER.close();
    }

    @Test
    void actualSmtpDeliveryCompletesOutboxAndClearsSensitivePayload() {
        String resetLink = "https://example.com/reset-password?token=smtp-success-secret";
        PasswordResetEmailTask task = createTask(
                "smtp-success@example.com",
                resetLink,
                "a".repeat(64)
        );

        worker.processPendingEmails();

        PasswordResetEmailTask reloaded = taskRepository.findById(task.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.COMPLETED);
        assertThat(reloaded.getRecipientEmail()).isNull();
        assertThat(reloaded.getEncryptedResetLink()).isNull();
        assertThat(SMTP_SERVER.messages()).hasSize(1);
        String deliveredMessage = SMTP_SERVER.messages().get(0);
        assertThat(deliveredMessage).contains("To: smtp-success@example.com");
        assertThat(decodeMimeBody(deliveredMessage)).contains("smtp-success-secret");
    }

    @Test
    void smtpFailureBacksOffThenAdminRequeueDeliversAfterRecovery() throws Exception {
        String adminToken = signupAndLoginAsAdmin("admin@example.com");
        String resetLink = "https://example.com/reset-password?token=smtp-recovery-secret";
        PasswordResetEmailTask task = createTask(
                "smtp-recovery@example.com",
                resetLink,
                "b".repeat(64)
        );
        SMTP_SERVER.rejectMessages();

        worker.processPendingEmails();

        PasswordResetEmailTask firstFailure =
                taskRepository.findById(task.getId()).orElseThrow();
        assertThat(firstFailure.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.PENDING);
        assertThat(firstFailure.getAttemptCount()).isEqualTo(1);
        assertThat(firstFailure.getNextAttemptAt()).isAfter(LocalDateTime.now());
        assertThat(firstFailure.getLastError()).contains("MailSendException");

        makeDue(firstFailure);
        worker.processPendingEmails();

        PasswordResetEmailTask deadLetter =
                taskRepository.findById(task.getId()).orElseThrow();
        assertThat(deadLetter.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.DEAD_LETTER);
        assertThat(deadLetter.getAttemptCount()).isEqualTo(2);
        assertThat(deadLetter.getEncryptedResetLink()).isNotBlank();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(adminToken);
        ResponseEntity<String> deadLetterList = restTemplate.exchange(
                "/api/admin/password-reset-email-tasks/dead-letter",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(deadLetterList.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(deadLetterList.getBody())
                .path("data")
                .path("content");
        assertThat(content)
                .anySatisfy(item -> assertThat(item.path("id").asLong()).isEqualTo(task.getId()));

        ResponseEntity<String> requeueResponse = restTemplate.exchange(
                "/api/admin/password-reset-email-tasks/" + task.getId() + "/requeue",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(requeueResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        PasswordResetEmailTask requeued =
                taskRepository.findById(task.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.PENDING);
        assertThat(requeued.getAttemptCount()).isZero();
        assertThat(requeued.getLastError()).isNull();

        SMTP_SERVER.acceptMessages();
        worker.processPendingEmails();

        PasswordResetEmailTask completed =
                taskRepository.findById(task.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PasswordResetEmailTaskStatus.COMPLETED);
        assertThat(completed.getRecipientEmail()).isNull();
        assertThat(completed.getEncryptedResetLink()).isNull();
        assertThat(SMTP_SERVER.messages()).hasSize(1);
        String deliveredMessage = SMTP_SERVER.messages().get(0);
        assertThat(deliveredMessage).contains("To: smtp-recovery@example.com");
        assertThat(decodeMimeBody(deliveredMessage)).contains("smtp-recovery-secret");
        assertThat(auditLogRepository.findAll()).hasSize(1);
    }

    private String decodeMimeBody(String message) {
        int bodyStart = message.indexOf("\n\n");
        if (bodyStart < 0) {
            throw new IllegalArgumentException("SMTP 메시지에서 MIME 본문을 찾을 수 없습니다.");
        }
        String encodedBody = message.substring(bodyStart + 2);
        return new String(
                Base64.getMimeDecoder().decode(encodedBody),
                StandardCharsets.UTF_8
        );
    }

    private PasswordResetEmailTask createTask(
            String email,
            String resetLink,
            String tokenHash
    ) {
        User user = userRepository.saveAndFlush(User.create(email, "hashed-password"));
        PasswordResetToken token = tokenRepository.saveAndFlush(PasswordResetToken.create(
                user,
                tokenHash,
                LocalDateTime.now().plusMinutes(30)
        ));
        taskService.enqueue(user, token, resetLink);
        return taskRepository.findAll().stream()
                .filter(candidate -> candidate.getUserId().equals(user.getId()))
                .findFirst()
                .orElseThrow();
    }

    private void makeDue(PasswordResetEmailTask task) {
        ReflectionTestUtils.setField(task, "nextAttemptAt", LocalDateTime.now().minusSeconds(1));
        taskRepository.saveAndFlush(task);
    }

    private String signupAndLogin(String email) throws Exception {
        Map<String, Object> request = Map.of(
                "email", email,
                "password", "password123",
                "agreedToTerms", true
        );
        restTemplate.postForEntity("/api/auth/signup", request, String.class);
        ResponseEntity<String> loginResponse =
                restTemplate.postForEntity("/api/auth/login", request, String.class);
        return objectMapper.readTree(loginResponse.getBody())
                .path("data")
                .path("accessToken")
                .asText();
    }

    // 공개 signup/login은 더 이상 ADMIN_EMAILS만으로 ADMIN을 부여하지 않는다(2026-08-03
    // P0 수정). 테스트에서 관리자 토큰이 필요하면 가입 후 role을 직접 동기화한다 —
    // JwtAuthenticationFilter가 매 요청마다 DB에서 role을 새로 조회하므로, 이미 발급된
    // 토큰도 이 동기화 이후 즉시 관리자 권한으로 동작한다.
    private String signupAndLoginAsAdmin(String email) throws Exception {
        String token = signupAndLogin(email);
        User user = userRepository.findByEmail(email).orElseThrow();
        user.syncRole(UserRole.ADMIN);
        userRepository.save(user);
        return token;
    }

    private static final class LoopbackSmtpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final Thread acceptThread;
        private volatile boolean rejectMessages;

        private LoopbackSmtpServer(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
            this.acceptThread = new Thread(this::acceptLoop, "test-smtp-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        }

        static LoopbackSmtpServer start() {
            try {
                ServerSocket serverSocket = new ServerSocket(
                        0,
                        50,
                        InetAddress.getLoopbackAddress()
                );
                return new LoopbackSmtpServer(serverSocket);
            } catch (IOException exception) {
                throw new IllegalStateException("테스트 SMTP 서버를 시작할 수 없습니다.", exception);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        List<String> messages() {
            return List.copyOf(messages);
        }

        void clearMessages() {
            messages.clear();
        }

        void rejectMessages() {
            rejectMessages = true;
        }

        void acceptMessages() {
            rejectMessages = false;
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread clientThread = new Thread(
                            () -> handleClient(socket),
                            "test-smtp-client"
                    );
                    clientThread.setDaemon(true);
                    clientThread.start();
                } catch (SocketException exception) {
                    if (running.get()) {
                        throw new IllegalStateException("테스트 SMTP accept가 실패했습니다.", exception);
                    }
                } catch (IOException exception) {
                    throw new IllegalStateException("테스트 SMTP 연결 처리에 실패했습니다.", exception);
                }
            }
        }

        private void handleClient(Socket socket) {
            try (
                    socket;
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    ));
                    BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8
                    ))
            ) {
                writeLine(writer, "220 localhost test SMTP");
                boolean readingData = false;
                StringBuilder message = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    if (readingData) {
                        if (".".equals(line)) {
                            messages.add(message.toString());
                            writeLine(writer, "250 queued");
                            readingData = false;
                            continue;
                        }
                        message.append(line).append('\n');
                        continue;
                    }

                    String command = line.toUpperCase();
                    if (command.startsWith("EHLO") || command.startsWith("HELO")) {
                        writeLine(writer, "250-localhost");
                        writeLine(writer, "250 8BITMIME");
                    } else if (command.startsWith("MAIL FROM") && rejectMessages) {
                        writeLine(writer, "451 simulated SMTP outage");
                    } else if (command.startsWith("MAIL FROM")
                            || command.startsWith("RCPT TO")
                            || command.startsWith("RSET")
                            || command.startsWith("NOOP")) {
                        writeLine(writer, "250 OK");
                    } else if (command.startsWith("DATA")) {
                        message.setLength(0);
                        readingData = true;
                        writeLine(writer, "354 end data with <CRLF>.<CRLF>");
                    } else if (command.startsWith("QUIT")) {
                        writeLine(writer, "221 bye");
                        return;
                    } else {
                        writeLine(writer, "250 OK");
                    }
                }
            } catch (IOException ignored) {
                // 실패 주입 시 JavaMail이 먼저 연결을 닫을 수 있습니다.
            }
        }

        private void writeLine(BufferedWriter writer, String line) throws IOException {
            writer.write(line);
            writer.write("\r\n");
            writer.flush();
        }

        @Override
        public void close() {
            running.set(false);
            try {
                serverSocket.close();
                acceptThread.join(2000);
            } catch (IOException exception) {
                throw new IllegalStateException("테스트 SMTP 서버 종료에 실패했습니다.", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("테스트 SMTP 서버 종료 대기가 중단됐습니다.", exception);
            }
        }
    }
}
