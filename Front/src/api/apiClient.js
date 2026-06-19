// fetch 기반 공통 API 요청, 오류 변환, 인증 만료 이벤트, blob 다운로드 처리를 제공한다.
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
export const API_UNAUTHORIZED_EVENT = "speakinsight:unauthorized";
export const DEFAULT_API_TIMEOUT_MS = 30000;

export class ApiRequestError extends Error {
  constructor(message, { status, code, cause } = {}) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
    this.code = code;
    if (cause) this.cause = cause;
  }
}

export function normalizeApiMessage(data, fallbackMessage = "요청 처리 중 오류가 발생했습니다.") {
  if (typeof data?.detail === "string") return data.detail;
  if (Array.isArray(data?.detail)) {
    return (
      data.detail
        .map((item) => item?.msg || item?.message)
        .filter(Boolean)
        .join("\n") || fallbackMessage
    );
  }
  if (typeof data?.message === "string") return data.message;
  if (typeof data?.error === "string") return data.error;
  if (typeof data?.summary?.error === "string") return data.summary.error;
  return fallbackMessage;
}

function emitUnauthorized(path) {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(API_UNAUTHORIZED_EVENT, { detail: { path } }));
}

export async function apiRequest(path, options = {}) {
  const { headers, signal, timeoutMs = DEFAULT_API_TIMEOUT_MS, ...fetchOptions } = options;
  const controller = new AbortController();
  let timedOut = false;
  const timeoutId =
    timeoutMs > 0
      ? window.setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs)
      : null;
  const abortRequest = () => controller.abort();

  if (signal?.aborted) {
    controller.abort();
  } else {
    signal?.addEventListener("abort", abortRequest, { once: true });
  }

  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      credentials: "include",
      ...fetchOptions,
      signal: controller.signal,
      headers: {
        ...(fetchOptions.body ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
    });
  } catch (requestError) {
    if (requestError.name === "AbortError" && timedOut) {
      throw new ApiRequestError("요청 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.", {
        code: "timeout",
        cause: requestError,
      });
    }
    throw requestError;
  } finally {
    if (timeoutId) window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortRequest);
  }

  if (response.status === 204) return null;

  let data = null;
  const contentType = response.headers.get("content-type") || "";
  if (contentType.includes("application/json")) {
    try {
      data = await response.json();
    } catch (parseError) {
      throw new ApiRequestError("서버 응답을 해석하지 못했습니다.", {
        status: response.status,
        code: "invalid_json",
        cause: parseError,
      });
    }
  } else if (response.ok) {
    throw new ApiRequestError("서버 응답 형식이 올바르지 않습니다.", {
      status: response.status,
      code: "invalid_response",
    });
  }

  if (!response.ok) {
    if (response.status === 401) emitUnauthorized(path);
    throw new ApiRequestError(normalizeApiMessage(data), { status: response.status });
  }
  return data;
}

export async function apiBlobRequest(path, options = {}) {
  const { headers, signal, timeoutMs = DEFAULT_API_TIMEOUT_MS, ...fetchOptions } = options;
  const controller = new AbortController();
  let timedOut = false;
  const timeoutId =
    timeoutMs > 0
      ? window.setTimeout(() => {
          timedOut = true;
          controller.abort();
        }, timeoutMs)
      : null;
  const abortRequest = () => controller.abort();

  if (signal?.aborted) {
    controller.abort();
  } else {
    signal?.addEventListener("abort", abortRequest, { once: true });
  }

  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      credentials: "include",
      ...fetchOptions,
      signal: controller.signal,
      headers,
    });
  } catch (requestError) {
    if (requestError.name === "AbortError" && timedOut) {
      throw new ApiRequestError("요청 시간이 초과되었습니다. 잠시 후 다시 시도해주세요.", {
        code: "timeout",
        cause: requestError,
      });
    }
    throw requestError;
  } finally {
    if (timeoutId) window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortRequest);
  }

  if (!response.ok) {
    if (response.status === 401) emitUnauthorized(path);
    let data = null;
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("application/json")) {
      try {
        data = await response.json();
      } catch {
        data = null;
      }
    }
    throw new ApiRequestError(normalizeApiMessage(data), { status: response.status });
  }

  return response.blob();
}
