// 홈 랜딩페이지의 히어로, 기능, 결과 예시, FAQ 등 섹션 UI를 제공한다.
import { Link } from "react-router-dom";

import {
  concerns,
  faqs,
  features,
  resultMetrics,
  resultTimeline,
  securityItems,
  steps,
  useCases,
} from "./homeContent";

export function HeroSection() {
  return (
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
            발표 목적과 청중을 설정하면 음성, 얼굴 방향, 자세, 제스처를 분석하고 다음 연습 행동과
            예상 질문을 제공합니다. 촬영 환경과 음성 품질에 따라 일부 항목은 측정 불가일 수
            있습니다.
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
              <small>AI 코칭 요청은 Ollama가 실행되는 서비스 서버에서 처리</small>
            </div>
            <div>
              <span>▣</span>
              <strong>개인정보 안전 보호</strong>
              <small>현재 구성의 Ollama 코칭은 외부 AI API 없이 처리</small>
            </div>
            <div>
              <span>◉</span>
              <strong>음성·얼굴 방향·자세 종합 분석</strong>
              <small>여러 지표를 한 번에</small>
            </div>
          </div>
        </div>

        <HeroVisual />
      </div>
    </section>
  );
}

function HeroVisual() {
  return (
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
        <span>얼굴 방향 예시</span>
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
  );
}

export function ConcernSection() {
  return (
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
  );
}

export function FeaturesSection() {
  return (
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
  );
}

export function ResultPreviewSection() {
  return (
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
            <p>화면 설명용 예시 점수</p>
          </div>
          <div className="result-metrics-panel">
            {resultMetrics.map((item) => {
              const [label, value] = item.split(" ");
              return (
                <div key={item}>
                  <span>{label}</span>
                  <strong>{value}</strong>
                </div>
              );
            })}
          </div>
          <div className="result-timeline-panel">
            <span>발표 타임라인</span>
            {resultTimeline.map((item) => (
              <div key={`${item.time}-${item.label}`}>
                <b>{item.time}</b>
                <i className={item.tone}>{item.label}</i>
              </div>
            ))}
          </div>
          <div className="result-feedback-panel">
            <div className="feedback-good">
              <strong>잘한 점</strong>
              <p>도입부에서 정면 방향을 안정적으로 유지했어요.</p>
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
  );
}

export function HowItWorksSection() {
  return (
    <section className="home-section" id="how">
      <div className="home-shell">
        <div className="home-section-heading">
          <span className="home-section-kicker">이용 방법</span>
          <h2>세 단계로 시작하는 발표 코칭</h2>
        </div>
        <div className="home-steps">
          {steps.map((step) => (
            <article key={step.number}>
              <span>{step.number}</span>
              <div className="step-icon">{step.icon}</div>
              <h3>{step.title}</h3>
              <p>{step.description}</p>
            </article>
          ))}
        </div>
        <div className="home-centered-action">
          <Link className="home-button primary" to="/upload">
            지금 분석 시작하기 →
          </Link>
        </div>
      </div>
    </section>
  );
}

export function UseCasesSection() {
  return (
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
  );
}

export function CoachSection() {
  return (
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
              <li>한국어 음절/분 지표와 실제 녹음을 함께 확인해보세요.</li>
            </ul>
          </div>
        </div>
        <div className="home-local-card">
          <span>⌬</span>
          <strong>Ollama 기반 로컬 AI</strong>
          <p>Ollama가 설치된 서비스 서버에서 AI 코칭 실행</p>
          <p>Ollama 코칭은 외부 AI API 없이 처리</p>
        </div>
      </div>
    </section>
  );
}

export function SecuritySection() {
  return (
    <section className="home-section home-security-section">
      <div className="home-shell">
        <div className="home-section-heading left">
          <span className="home-section-kicker">개인정보 보호 및 보안</span>
          <h2>발표 영상과 분석 결과를 안전하게</h2>
        </div>
        <div className="home-security-grid">
          {securityItems.map((item) => (
            <article key={item.title}>
              <span>{item.icon}</span>
              <div>
                <h3>{item.title}</h3>
                <p>{item.description}</p>
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

export function FaqSection() {
  return (
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
  );
}

export function FinalCtaSection() {
  return (
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
  );
}

export function HomeFooter() {
  return (
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
  );
}
