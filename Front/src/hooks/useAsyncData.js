// AbortController와 로딩·오류 상태를 포함한 비동기 데이터 로딩 공통 훅이다.
import { useCallback, useEffect, useState } from "react";

export default function useAsyncData(loader) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const reload = useCallback(
    async (signal) => {
      setLoading(true);
      setError("");
      try {
        setData(await loader(signal));
      } catch (requestError) {
        if (requestError.name !== "AbortError") {
          setError(requestError.message || "요청을 처리하지 못했습니다.");
        }
      } finally {
        if (!signal?.aborted) setLoading(false);
      }
    },
    [loader],
  );

  useEffect(() => {
    const controller = new AbortController();
    reload(controller.signal);
    return () => controller.abort();
  }, [reload]);

  return { data, error, loading, reload };
}
