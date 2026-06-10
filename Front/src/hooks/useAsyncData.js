import { useCallback, useEffect, useState } from "react";

export default function useAsyncData(loader) {
  const [data, setData] = useState(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setData(await loader());
    } catch (requestError) {
      setError(requestError.message || "요청을 처리하지 못했습니다.");
    } finally {
      setLoading(false);
    }
  }, [loader]);

  useEffect(() => {
    reload();
  }, [reload]);

  return { data, error, loading, reload };
}
