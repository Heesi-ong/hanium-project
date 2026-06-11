import { Link } from "react-router-dom";

import "./HomePage.css";

const concerns = [
  {
    icon: "01",
    title: "객관적인 말하기 습관 파악",
    description: "말하는 속도와 음량, 침묵 구간을 데이터로 확인합니다.",
  },
  {
    icon: "02",
    title: "표정과 시선 흐름 확인",
    description: "발표 중 표정 변화와 정면 응시 비율을 분석합니다.",
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

const features = [
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
    title: "표정과 시선 분석",
    description: "표정 변화와 시선 분산 정도를 장면별로 확인합니다.",
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
    description: "자세 균형과 발표 중 손동작의 자연스러움을 확인합니다.",
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

const useCases = [
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
    description: "시선과 말하기 습관을 객관적으로 확인하세요.",
  },
  {
    label: "비즈니스 · 업무 발표",
    className: "business",
    description: "핵심 메시지를 효과적으로 전달하세요.",
  },
];

const faqs = [
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
    "음성, 말하기 속도, 침묵, 필러 단어, 표정, 시선, 자세, 제스처를 종합 분석합니다.",
  ],
  [
    "AI 코치는 어떤 역할을 하나요?",
    "분석 결과와 발표 목적을 바탕으로 예상 질문에 답하고 후속 질문과 개선 방법을 연습할 수 있습니다.",
  ],
];

function HomePage() {
  return (
    <main className="home-page">
      <section className="home-hero">
        <div className="home-hero-glow home-hero-glow-one" />
        <div className="home-hero-glow home-hero-glow-two" />

        <div className="home-shell home-hero-layout">
          <div className="home-hero-copy">
            <div className="home-eyebrow">
              <span>✦</span>
              AI가 당신의 발표를 객관적으로 코칭합니다
            </div>
            <h1>
              발표 영상을 데이터로 분석하고,
              <strong>더 나은 발표를 준비하세요</strong>
            </h1>
            <p>
              발표 목적과 청중을 설정하면 음성, 표정, 자세, 제스처를 분석하고 다음 연습 행동과 예상
              질문을 제공합니다.
            </p>

            <div className="home-hero-actions">
              <Link className="home-button primary" to="/upload">
                발표 분석 시작하기 <span>→</span>
              </Link>
              <a className="home-button ghost" href="#result-preview">
                분석 결과 예시 보기
              </a>
            </div>

            <div className="home-trust-row">
              <div>
                <span>⌬</span>
                <strong>Ollama 기반 로컬 AI</strong>
                <small>내 데이터는 내 PC에서</small>
              </div>
              <div>
                <span>▣</span>
                <strong>개인정보 안전 보호</strong>
                <small>외부 API 전송 없이</small>
              </div>
              <div>
                <span>◉</span>
                <strong>음성·표정·자세 종합 분석</strong>
                <small>여러 지표를 한 번에</small>
              </div>
            </div>
          </div>

          <div className="home-hero-visual" aria-label="발표 분석 화면 예시">
            <div className="hero-data-card voice-card">
              <span>음성 분석</span>
              <div className="home-wave">
                <i />
                <i />
                <i />
                <i />
                <i />
                <i />
                <i />
                <i />
                <i />
              </div>
            </div>
            <div className="hero-data-card speed-card">
              <span>말하기 속도</span>
              <strong>
                138 <small>wpm</small>
              </strong>
            </div>
            <div className="hero-data-card gesture-card">
              <span>주요 제스처</span>
              <strong>
                12 <small>회</small>
              </strong>
            </div>
            <div className="hero-data-card total-card">
              <span>종합 점수</span>
              <div className="hero-score-ring">
                <strong>84</strong>
                <small>/100</small>
              </div>
            </div>
            <div className="hero-data-card gaze-card">
              <span>시선 분포</span>
              <div className="hero-heatmap">
                <i />
                <i />
                <i />
              </div>
            </div>

            <div className="presenter-figure">
              <div className="presenter-head">
                <span className="face-scan" />
              </div>
              <div className="presenter-body">
                <span className="pose-dot p1" />
                <span className="pose-dot p2" />
                <span className="pose-dot p3" />
                <span className="pose-dot p4" />
                <span className="pose-line l1" />
                <span className="pose-line l2" />
                <span className="pose-line l3" />
                <span className="pose-line l4" />
              </div>
              <div className="presenter-hand left" />
              <div className="presenter-hand right" />
            </div>

            <div className="hero-timeline-card">
              <span>발표 타임라인</span>
              <div>
                <i />
                <i />
                <i />
                <i />
                <i />
                <i />
              </div>
              <small>
                <b>00:00</b>
                <b>02:30</b>
                <b>05:00</b>
                <b>07:30</b>
              </small>
            </div>
          </div>
        </div>
      </section>

      <section className="home-section home-concern-section">
        <div className="home-shell home-concern-layout">
          <div className="home-section-heading left">
            <span className="home-section-kicker">발표 고민 해결</span>
            <h2>내 발표에서 무엇을 고쳐야 할지 막막하셨나요?</h2>
            <p>혼자서는 알기 어려운 발표 습관을 데이터로 명확하게 보여드립니다.</p>
          </div>
          <div className="home-concern-grid">
            {concerns.map((item) => (
              <article className="home-concern-card" key={item.title}>
                <span className="home-number-icon">{item.icon}</span>
                <h3>{item.title}</h3>
                <p>{item.description}</p>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="home-section" id="features">
        <div className="home-shell">
          <div className="home-section-heading">
            <span className="home-section-kicker">주요 기능</span>
            <h2>발표의 모든 순간을 하나의 분석으로</h2>
            <p>기능별 결과는 별도 화면에서 더 자세하게 확인할 수 있습니다.</p>
          </div>
          <div className="home-feature-grid">
            {features.map((feature) => (
              <article className={`home-feature-card ${feature.className}`} key={feature.title}>
                <div className="home-feature-icon">{feature.icon}</div>
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
                <div className="home-feature-visual">{feature.visual}</div>
                <Link to={feature.className === "coach" ? "/chat" : "/upload"}>
                  자세히 보기 <span>→</span>
                </Link>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="home-section home-result-section" id="result-preview">
        <div className="home-shell">
          <div className="home-section-heading split-heading">
            <div>
              <span className="home-section-kicker">분석 결과 예시</span>
              <h2>결과를 이해하고 바로 연습으로 연결하세요</h2>
            </div>
            <Link className="home-text-link" to="/results">
              분석 결과 자세히 보기 →
            </Link>
          </div>
          <div className="home-result-board">
            <div className="result-score-panel">
              <span>종합 점수</span>
              <div className="result-score-ring">
                <strong>84</strong>
                <small>/100</small>
              </div>
              <p>상위 18%</p>
            </div>
            <div className="result-metrics-panel">
              {["음성 88", "표정 82", "시선 78", "자세 86", "제스처 80", "전달력 85"].map(
                (item) => {
                  const [label, value] = item.split(" ");
                  return (
                    <div key={item}>
                      <span>{label}</span>
                      <strong>{value}</strong>
                    </div>
                  );
                },
              )}
            </div>
            <div className="result-timeline-panel">
              <span>발표 타임라인</span>
              <div>
                <b>01:15</b>
                <i className="bad">말하기 속도 빠름</i>
              </div>
              <div>
                <b>03:42</b>
                <i className="normal">주변 시선 증가</i>
              </div>
              <div>
                <b>05:20</b>
                <i className="normal">서던 발음</i>
              </div>
              <div>
                <b>07:10</b>
                <i className="good">좋은 몸짓과 제스처</i>
              </div>
            </div>
            <div className="result-feedback-panel">
              <div className="feedback-good">
                <strong>잘한 점</strong>
                <p>도입부에서 청중과 눈맞춤을 잘 했어요.</p>
                <p>핵심 내용을 명확하게 전달했어요.</p>
              </div>
              <div className="feedback-bad">
                <strong>개선할 점</strong>
                <p>중반부 말하기 속도가 빨라졌어요.</p>
                <p>주요 내용을 조금 더 천천히 말해보세요.</p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="home-section" id="how">
        <div className="home-shell">
          <div className="home-section-heading">
            <span className="home-section-kicker">이용 방법</span>
            <h2>세 단계로 시작하는 발표 코칭</h2>
          </div>
          <div className="home-steps">
            <article>
              <span>1</span>
              <div className="step-icon">⇧</div>
              <h3>목표 설정 및 영상 업로드</h3>
              <p>발표 목적, 청중, 핵심 메시지를 정하고 영상을 준비합니다.</p>
            </article>
            <article>
              <span>2</span>
              <div className="step-icon">⌘</div>
              <h3>AI가 자동 분석</h3>
              <p>음성, 표정, 자세와 제스처를 종합 분석합니다.</p>
            </article>
            <article>
              <span>3</span>
              <div className="step-icon">↗</div>
              <h3>결과 확인 및 반복 연습</h3>
              <p>개선 행동 3가지와 예상 질문을 연습하고 성장 변화를 확인합니다.</p>
            </article>
          </div>
          <div className="home-centered-action">
            <Link className="home-button primary" to="/upload">
              지금 분석 시작하기 →
            </Link>
          </div>
        </div>
      </section>

      <section className="home-section home-use-section" id="use-cases">
        <div className="home-shell">
          <div className="home-section-heading left">
            <span className="home-section-kicker">활용 분야</span>
            <h2>중요한 발표가 있는 모든 순간에</h2>
          </div>
          <div className="home-use-grid">
            {useCases.map((item) => (
              <article className={`home-use-card ${item.className}`} key={item.label}>
                <div className="use-scene">
                  <span />
                  <i />
                  <b />
                </div>
                <div>
                  <h3>{item.label}</h3>
                  <p>{item.description}</p>
                </div>
              </article>
            ))}
          </div>
        </div>
      </section>

      <section className="home-section home-coach-section">
        <div className="home-shell home-coach-card">
          <div className="home-coach-copy">
            <span className="home-section-kicker light">로컬 AI 코치</span>
            <h2>AI 코치와 함께 더 빠르게 성장하세요</h2>
            <p>분석 결과를 바탕으로 개선 방법과 구체적인 연습 방법을 질문해보세요.</p>
            <Link className="home-button primary" to="/chat">
              AI 코치와 대화하기 →
            </Link>
          </div>
          <div className="home-coach-chat">
            <p className="question">말하는 속도를 어떻게 개선할 수 있을까요?</p>
            <div className="answer">
              <strong>발표 중 속도가 빨라지는 구간이 보여요.</strong>
              <ul>
                <li>문장 사이에 1~2초의 의도적인 쉼을 넣어보세요.</li>
                <li>분당 120~140 단어를 목표로 연습해보세요.</li>
              </ul>
            </div>
          </div>
          <div className="home-local-card">
            <span>⌬</span>
            <strong>Ollama 기반 로컬 AI</strong>
            <p>내 PC에서 안전하게 실행</p>
            <p>외부 API 전송 없이 사용</p>
          </div>
        </div>
      </section>

      <section className="home-section home-security-section">
        <div className="home-shell">
          <div className="home-section-heading left">
            <span className="home-section-kicker">개인정보 보호 및 보안</span>
            <h2>발표 영상과 분석 결과를 안전하게</h2>
          </div>
          <div className="home-security-grid">
            <article>
              <span>⌬</span>
              <div>
                <h3>로컬 AI 기반 분석</h3>
                <p>Ollama 기반 AI를 로컬 환경에서 활용합니다.</p>
              </div>
            </article>
            <article>
              <span>♢</span>
              <div>
                <h3>안전한 영상 관리</h3>
                <p>업로드한 영상과 결과를 명확하게 관리합니다.</p>
              </div>
            </article>
            <article>
              <span>◉</span>
              <div>
                <h3>분석 결과 보호</h3>
                <p>사용자 결과를 다른 사용자와 분리합니다.</p>
              </div>
            </article>
            <article>
              <span>♧</span>
              <div>
                <h3>데이터 삭제 가능</h3>
                <p>분석 이력에서 불필요한 결과를 삭제합니다.</p>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section className="home-section home-faq-section">
        <div className="home-shell">
          <div className="home-section-heading left">
            <span className="home-section-kicker">자주 묻는 질문</span>
            <h2>서비스 이용 전 확인해보세요</h2>
          </div>
          <div className="home-faq-grid">
            {faqs.map(([question, answer]) => (
              <details key={question}>
                <summary>
                  {question}
                  <span>+</span>
                </summary>
                <p>{answer}</p>
              </details>
            ))}
          </div>
        </div>
      </section>

      <section className="home-final-cta">
        <div className="home-shell">
          <span>✦</span>
          <h2>다음 발표는 더 자신 있게 준비하세요</h2>
          <p>발표 영상을 업로드하고 지금 개선할 부분을 확인해보세요.</p>
          <div className="home-hero-actions centered">
            <Link className="home-button primary" to="/upload">
              무료로 발표 분석 시작하기 →
            </Link>
            <a className="home-button ghost" href="#features">
              서비스 기능 살펴보기
            </a>
          </div>
        </div>
      </section>

      <footer className="home-footer">
        <div className="home-shell home-footer-grid">
          <div>
            <strong>✦ SpeakInsight</strong>
            <p>AI 발표 분석과 로컬 코칭으로 더 나은 발표를 준비하세요.</p>
          </div>
          <div>
            <b>서비스</b>
            <a href="#features">주요 기능</a>
            <a href="#how">이용 방법</a>
            <a href="#use-cases">활용 분야</a>
          </div>
          <div>
            <b>기능</b>
            <Link to="/upload">영상 분석</Link>
            <Link to="/results">분석 이력</Link>
            <Link to="/chat">AI 코치</Link>
          </div>
          <div>
            <b>기술</b>
            <span>FastAPI</span>
            <span>React</span>
            <span>Ollama</span>
          </div>
        </div>
      </footer>
    </main>
  );
}

export default HomePage;
