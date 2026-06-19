// XMLHttpRequest로 업로드 진행률을 추적하며 발표 영상 업로드 요청을 보낸다.
import { API_BASE_URL, normalizeApiMessage } from "./apiClient";

export function uploadAnalyzeVideo(file, onUploadProgress, signal) {
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

    xhr.onabort = () => {
      reject(new DOMException("업로드가 취소되었습니다.", "AbortError"));
    };

    if (signal?.aborted) {
      xhr.abort();
      return;
    }
    signal?.addEventListener("abort", () => xhr.abort(), { once: true });
    xhr.send(formData);
  });
}
