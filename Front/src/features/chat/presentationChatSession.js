// 분석 결과에서 채팅으로 넘어갈 때 중복 대화 생성을 줄이기 위한 세션 키를 관리한다.
const PREFIX = "speakinsight:chat-context:";

export const presentationContextDigest = (value) => {
  const input = String(value || "");
  let hash = 2166136261;
  for (let index = 0; index < input.length; index += 1) {
    hash ^= input.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return `${(hash >>> 0).toString(36)}-${input.length.toString(36)}`;
};

export const presentationChatSessionKey = (userId, contextKey) =>
  `${PREFIX}user:${userId || "anonymous"}:${contextKey}`;

export const clearPresentationChatSession = () => {
  Object.keys(window.sessionStorage)
    .filter((key) => key.startsWith(PREFIX))
    .forEach((key) => window.sessionStorage.removeItem(key));
};
