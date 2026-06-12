const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

const DEFAULT_ERROR_MESSAGE = "요청 처리 중 오류가 발생했습니다.";

function normalizeApiMessage(data, fallbackMessage = DEFAULT_ERROR_MESSAGE) {
  const detail = data?.detail;

  if (typeof detail === "string") {
    return detail;
  }

  if (Array.isArray(detail)) {
    return (
      detail
        .map((item) => item?.msg || item?.message)
        .filter(Boolean)
        .join("\n") || fallbackMessage
    );
  }

  if (typeof data?.message === "string") {
    return data.message;
  }

  if (typeof data?.error === "string") {
    return data.error;
  }

  if (typeof data?.summary?.error === "string") {
    return data.summary.error;
  }

  return fallbackMessage;
}

async function parseResponse(response) {
  let data = null;

  try {
    data = await response.json();
  } catch {
    data = null;
  }

  if (!response.ok) {
    throw new Error(normalizeApiMessage(data));
  }

  return data;
}

export function uploadAnalyzeVideo(file, onUploadProgress) {
  return new Promise((resolve, reject) => {
    const formData = new FormData();
    formData.append("file", file);

    const xhr = new XMLHttpRequest();

    xhr.open("POST", `${API_BASE_URL}/analyze/upload`);
    xhr.withCredentials = true;
    xhr.setRequestHeader("Idempotency-Key", crypto.randomUUID());

    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onUploadProgress) {
        const percent = Math.round((event.loaded / event.total) * 100);
        onUploadProgress(percent);
      }
    };

    xhr.onload = () => {
      try {
        const data = JSON.parse(xhr.responseText);

        if (xhr.status < 200 || xhr.status >= 300) {
          reject(new Error(normalizeApiMessage(data)));
          return;
        }

        resolve(data);
      } catch {
        reject(new Error("서버 응답을 해석할 수 없습니다."));
      }
    };

    xhr.onerror = () => {
      reject(new Error("서버에 연결할 수 없습니다. 백엔드 실행 상태와 CORS 설정을 확인해주세요."));
    };

    xhr.send(formData);
  });
}

export async function getAnalyzeResults(params = {}, signal) {
  const query = new URLSearchParams(
    Object.entries(params).filter(
      ([, value]) => value !== "" && value !== null && value !== undefined,
    ),
  );
  const response = await fetch(`${API_BASE_URL}/analyze/results?${query}`, {
    credentials: "include",
    signal,
  });

  return await parseResponse(response);
}

export async function getAnalyzeSummary(resultId) {
  const response = await fetch(`${API_BASE_URL}/analyze/result/${resultId}/summary`, {
    credentials: "include",
  });

  return await parseResponse(response);
}

export async function getAnalyzeSections(resultId, signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/result/${resultId}/sections`, {
    credentials: "include",
    signal,
  });

  return await parseResponse(response);
}

export async function getTimelineChart(resultId, signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/result/${resultId}/timeline/chart`, {
    credentials: "include",
    signal,
  });

  return await parseResponse(response);
}

export async function deleteAnalyzeResult(resultId) {
  const response = await fetch(`${API_BASE_URL}/analyze/result/${resultId}`, {
    method: "DELETE",
    credentials: "include",
  });

  return await parseResponse(response);
}

export async function getAnalyzeJob(resultId, signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/job/${resultId}`, {
    credentials: "include",
    signal,
  });

  return await parseResponse(response);
}

export async function cancelAnalyzeJob(resultId) {
  const response = await fetch(`${API_BASE_URL}/analyze/job/${resultId}/cancel`, {
    method: "POST",
    credentials: "include",
  });

  return await parseResponse(response);
}

export async function retryAnalyzeJob(resultId) {
  const response = await fetch(`${API_BASE_URL}/analyze/job/${resultId}/retry`, {
    method: "POST",
    credentials: "include",
  });

  return await parseResponse(response);
}

export async function getAnalyzeGrowth(signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/growth`, {
    credentials: "include",
    signal,
  });

  return await parseResponse(response);
}

export async function savePracticeContext(resultId, context) {
  const response = await fetch(`${API_BASE_URL}/analyze/practice/${resultId}`, {
    method: "PUT",
    credentials: "include",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(context),
  });
  return await parseResponse(response);
}

export async function getPracticeCoaching(resultId, signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/practice/${resultId}`, {
    credentials: "include",
    signal,
  });
  return await parseResponse(response);
}

export async function getPracticeGrowth(signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/practice/growth/all`, {
    credentials: "include",
    signal,
  });
  return await parseResponse(response);
}

export async function getPracticePurposes(signal) {
  const response = await fetch(`${API_BASE_URL}/analyze/practice/purposes`, {
    credentials: "include",
    signal,
  });
  return await parseResponse(response);
}

export function getAnalyzeReportUrl(resultId) {
  return `${API_BASE_URL}/analyze/result/${resultId}/report.md`;
}
