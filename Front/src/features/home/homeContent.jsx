// 홈 랜딩페이지에서 반복 렌더링하는 문구, 카드, FAQ 데이터를 모아둔다.
export const concerns = [
  {
    icon: "01",
    title: "객관적인 말하기 습관 파악",
    description: "말하는 속도와 음량, 침묵 구간을 데이터로 확인합니다.",
  },
  {
    icon: "02",
    title: "얼굴 방향 흐름 확인",
    description: "발표 중 얼굴 감지와 정면 방향 안정성을 보조 지표로 확인합니다.",
  },
  {
    icon: "03",
    title: "자세와 제스처 점검",
    description: "몸의 균형과 손동작을 장면별로 살펴봅니다.",
  },
  {
    icon: "04",
    title: "개선 방법까지 연결",
    description: "목적별 개선 과제와 예상 질문으로 다음 연습을 시작합니다.",
  },
];

export const features = [
  {
    className: "voice",
    icon: "⌁",
    title: "음성 및 말하기 습관 분석",
    description: "말하기 속도, 음량, 침묵 구간과 필러 단어를 분석합니다.",
    visual: (
      <div className="home-wave-mini">
        <span />
        <span />
        <span />
        <span />
        <span />
        <span />
        <span />
      </div>
    ),
  },
  {
    className: "face",
    icon: "◎",
    title: "얼굴 방향 분석",
    description: "얼굴 특징점 기반 정면 방향 안정성을 장면별로 확인합니다.",
    visual: (
      <div className="home-face-grid">
        <span>☺</span>
        <span className="heatmap-mini" />
      </div>
    ),
  },
  {
    className: "pose",
    icon: "⌘",
    title: "자세와 제스처 분석",
    description: "자세 균형과 발표 중 손목 위치 변화 횟수를 확인합니다.",
    visual: (
      <div className="home-pose-mini">
        <span />
        <span />
        <span />
      </div>
    ),
  },
  {
    className: "timeline",
    icon: "≋",
    title: "발표 타임라인",
    description: "개선이 필요한 순간을 시간 흐름에 따라 빠르게 찾습니다.",
    visual: (
      <div className="home-timeline-mini">
        <span />
        <span />
        <span />
        <span />
      </div>
    ),
  },
  {
    className: "score",
    icon: "☆",
    title: "목적별 개선 계획",
    description: "발표 목적과 청중에 맞춰 다음 연습에서 바꿀 행동 3가지를 제안합니다.",
    visual: (
      <div className="home-score-mini">
        <strong>84</strong>
        <span />
      </div>
    ),
  },
  {
    className: "coach",
    icon: "◌",
    title: "Ollama 기반 AI 코치",
    description: "분석 결과와 연습 방법을 로컬 AI 코치에게 질문합니다.",
    visual: (
      <div className="home-chat-mini">
        <span />
        <span />
        <span />
      </div>
    ),
  },
];

export const resultMetrics = [
  "음성 88",
  "얼굴 감지 82",
  "얼굴 방향 78",
  "자세 86",
  "제스처 80",
  "전달력 85",
];

export const resultTimeline = [
  { time: "01:15", tone: "bad", label: "말하기 속도 빠름" },
  { time: "03:42", tone: "normal", label: "정면 방향 이탈 증가" },
  { time: "05:20", tone: "normal", label: "안정적인 발음" },
  { time: "07:10", tone: "good", label: "손동작 변화 구간" },
];

export const steps = [
  {
    number: "1",
    icon: "⇧",
    title: "목표 설정 및 영상 업로드",
    description: "발표 목적, 청중, 핵심 메시지를 정하고 영상을 준비합니다.",
  },
  {
    number: "2",
    icon: "⌘",
    title: "AI가 자동 분석",
    description: "음성, 얼굴 방향, 자세와 제스처를 종합 분석합니다.",
  },
  {
    number: "3",
    icon: "↗",
    title: "결과 확인 및 반복 연습",
    description: "개선 행동 3가지와 예상 질문을 연습하고 성장 변화를 확인합니다.",
  },
];

export const useCases = [
  {
    label: "대학 수업 발표",
    className: "lecture",
    description: "과제와 졸업 발표를 더 자신 있게 준비하세요.",
  },
  {
    label: "팀 프로젝트 · 공모전",
    className: "team",
    description: "팀 발표의 전달력과 역할 흐름을 점검하세요.",
  },
  {
    label: "취업 면접 · 자기소개",
    className: "interview",
    description: "얼굴 방향과 말하기 습관을 객관적으로 확인하세요.",
  },
  {
    label: "비즈니스 · 업무 발표",
    className: "business",
    description: "핵심 메시지를 효과적으로 전달하세요.",
  },
];

export const securityItems = [
  {
    icon: "⌬",
    title: "Ollama 코칭 처리 범위",
    description: "현재 배포 구성에서는 서비스 서버의 Ollama를 AI 코칭에 활용합니다.",
  },
  {
    icon: "i",
    title: "분석 신뢰도 안내",
    description:
      "화면·음성 데이터가 부족한 항목은 점수가 아닌 측정 불가로 표시하며, 결과는 발표 연습을 위한 보조 지표입니다.",
  },
  {
    icon: "♢",
    title: "안전한 영상 관리",
    description: "업로드한 영상과 결과를 명확하게 관리합니다.",
  },
  {
    icon: "◉",
    title: "분석 결과 보호",
    description: "사용자 결과를 다른 사용자와 분리합니다.",
  },
  {
    icon: "♧",
    title: "데이터 삭제 가능",
    description: "분석 이력에서 불필요한 결과를 삭제합니다.",
  },
];

export const faqs = [
  [
    "어떤 영상 형식을 지원하나요?",
    "MP4, MOV, AVI, MKV 형식을 지원하며 최대 500MB까지 업로드할 수 있습니다.",
  ],
  [
    "분석에는 얼마나 걸리나요?",
    "영상 길이와 기기 성능에 따라 수 분이 걸릴 수 있으며 분석 완료 후 상세 결과로 이동합니다.",
  ],
  [
    "어떤 항목을 분석하나요?",
    "음성, 말하기 속도, 침묵, 필러 단어, 얼굴 감지, 얼굴 방향, 자세, 제스처를 종합 분석합니다.",
  ],
  [
    "AI 코치는 어떤 역할을 하나요?",
    "분석 결과와 발표 목적을 바탕으로 예상 질문에 답하고 후속 질문과 개선 방법을 연습할 수 있습니다.",
  ],
];
