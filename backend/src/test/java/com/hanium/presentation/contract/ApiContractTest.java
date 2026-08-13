package com.hanium.presentation.contract;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    private static final Pattern API_CLIENT_CALL_PATTERN = Pattern.compile(
            "apiClient\\.(get|post|put|delete)\\s*\\(\\s*([\"`])([^\"`]+)\\2",
            Pattern.MULTILINE
    );
    private static final Pattern TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\$\\{[^}]+}");
    private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{[^/}]+}");

    @Autowired
    private RequestMappingInfoHandlerMapping requestMappingHandlerMapping;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void frontendApiCallsMatchBackendRoutes() throws IOException {
        List<BackendRoute> backendRoutes = collectBackendRoutes();
        List<FrontendApiCall> frontendApiCalls = collectFrontendApiCalls();

        assertThat(frontendApiCalls)
                .as("frontend/src/api/*.js에서 추출한 API 호출이 있어야 합니다.")
                .isNotEmpty();

        List<String> unmatchedCalls = frontendApiCalls.stream()
                .filter(frontendApiCall -> backendRoutes.stream()
                        .noneMatch(backendRoute -> backendRoute.matches(frontendApiCall)))
                .map(FrontendApiCall::toString)
                .sorted()
                .toList();

        assertThat(unmatchedCalls)
                .as("프론트 API 호출은 실제 backend /api/** 라우트와 일치해야 합니다. backendRoutes=%s", backendRoutes)
                .isEmpty();
    }

    @Test
    void openApiDocsArePubliclyAvailable() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andReturn();

        Path contractDirectory = Path.of("build", "contracts");
        Files.createDirectories(contractDirectory);
        Files.writeString(
                contractDirectory.resolve("backend-openapi.json"),
                result.getResponse().getContentAsString()
        );
    }

    private List<BackendRoute> collectBackendRoutes() {
        List<BackendRoute> backendRoutes = new ArrayList<>();

        for (var entry : requestMappingHandlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo mappingInfo = entry.getKey();
            HandlerMethod handlerMethod = entry.getValue();
            if (!handlerMethod.getBeanType().getPackageName().startsWith("com.hanium.presentation")) {
                continue;
            }

            Set<HttpMethod> methods = mappingInfo.getMethodsCondition().getMethods().stream()
                    .map(requestMethod -> HttpMethod.valueOf(requestMethod.name()))
                    .collect(java.util.stream.Collectors.toSet());
            if (methods.isEmpty()) {
                methods = Set.of(
                        HttpMethod.GET,
                        HttpMethod.POST,
                        HttpMethod.PUT,
                        HttpMethod.PATCH,
                        HttpMethod.DELETE
                );
            }

            Set<String> paths = mappingInfo.getPathPatternsCondition() != null
                    ? mappingInfo.getPathPatternsCondition().getPatternValues()
                    : mappingInfo.getPatternsCondition().getPatterns();

            for (String path : paths) {
                if (!path.startsWith("/api/")) {
                    continue;
                }

                for (HttpMethod method : methods) {
                    backendRoutes.add(new BackendRoute(method, path));
                }
            }
        }

        return backendRoutes.stream()
                .sorted(Comparator.comparing(BackendRoute::path).thenComparing(route -> route.method().name()))
                .toList();
    }

    private List<FrontendApiCall> collectFrontendApiCalls() throws IOException {
        Path frontendApiDirectory = Path.of(System.getProperty("user.dir"))
                .getParent()
                .resolve("frontend")
                .resolve("src")
                .resolve("api");

        assertThat(frontendApiDirectory)
                .as("frontend API 디렉토리를 찾을 수 있어야 합니다.")
                .isDirectory();

        List<FrontendApiCall> frontendApiCalls = new ArrayList<>();
        try (Stream<Path> paths = Files.list(frontendApiDirectory)) {
            for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".js")).toList()) {
                String source = Files.readString(path);
                Matcher matcher = API_CLIENT_CALL_PATTERN.matcher(source);
                while (matcher.find()) {
                    String rawPath = matcher.group(3);
                    if (!rawPath.startsWith("/api/")) {
                        continue;
                    }

                    frontendApiCalls.add(new FrontendApiCall(
                            HttpMethod.valueOf(matcher.group(1).toUpperCase(Locale.ROOT)),
                            normalizeFrontendPath(rawPath),
                            path.getFileName().toString()
                    ));
                }
            }
        }

        return frontendApiCalls;
    }

    private String normalizeFrontendPath(String rawPath) {
        return TEMPLATE_VARIABLE_PATTERN.matcher(rawPath).replaceAll("{var}");
    }

    private record BackendRoute(
            HttpMethod method,
            String path
    ) {

        boolean matches(FrontendApiCall frontendApiCall) {
            return method.equals(frontendApiCall.method())
                    && toRegex(path).matcher(frontendApiCall.normalizedPath()).matches();
        }

        private Pattern toRegex(String routePath) {
            String regex = PATH_VARIABLE_PATTERN.matcher(Pattern.quote(routePath))
                    .replaceAll("\\\\E[^/]+\\\\Q");
            return Pattern.compile("^" + regex + "$");
        }
    }

    private record FrontendApiCall(
            HttpMethod method,
            String normalizedPath,
            String sourceFile
    ) {

        @Override
        public String toString() {
            return method + " " + normalizedPath + " (" + sourceFile + ")";
        }
    }
}
