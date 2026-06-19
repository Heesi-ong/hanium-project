// 관리자 화면에서 반복되는 요약 카드와 시스템 점검 카드를 제공한다.
import Card from "../../components/ui/Card";

const toneClassName = {
  danger: "admin-card-danger",
  info: "admin-card-info",
  neutral: "admin-card-neutral",
  success: "admin-card-success",
  warning: "admin-card-warning",
};

export function AdminMetricCard({ details = [], title, tone = "neutral", value }) {
  return (
    <Card as="article" className={`admin-metric-card ${toneClassName[tone] || ""}`}>
      <div className="admin-card-label">{title}</div>
      <strong>{value}</strong>
      {details.map((detail) => (
        <p key={detail}>{detail}</p>
      ))}
    </Card>
  );
}

export function AdminPolicyPanel() {
  return (
    <Card className="admin-policy-card">
      <div>
        <p className="admin-card-label">관리자 정책</p>
        <h2>허용된 범위 안에서만 사용자 상태를 관리합니다.</h2>
      </div>
      <ul>
        <li>이메일, 가입일, 계정 상태만 조회합니다.</li>
        <li>사용자 활성화와 정지만 웹에서 허용합니다.</li>
        <li>관리자 권한 변경은 CLI에서만 수행합니다.</li>
        <li>분석 상세 결과와 원본 영상은 관리자 화면에 노출하지 않습니다.</li>
      </ul>
    </Card>
  );
}

export function SystemCheckCard({ children, ok, title }) {
  return (
    <Card
      as="article"
      className={`admin-system-card ${ok ? "admin-card-success" : "admin-card-danger"}`}
    >
      <div className="admin-system-card-header">
        <h2>{title}</h2>
        <strong className={ok ? "status-ok" : "status-error"}>{ok ? "정상" : "점검 필요"}</strong>
      </div>
      {children}
    </Card>
  );
}
