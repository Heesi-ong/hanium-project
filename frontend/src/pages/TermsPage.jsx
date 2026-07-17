import { Link } from "react-router-dom";

function TermsPage() {
    return (
        <section className="page-section">
            <article className="upload-card policy-card">
                <p className="eyebrow">Terms Draft</p>
                <h1>이용약관</h1>

                <div className="policy-disclaimer">
                    이 문서는 초안이며 법률 전문가의 검토를 거치지 않았습니다.
                    실제 서비스 출시 전 반드시 법률 전문가 검토가 필요합니다.
                </div>

                <div className="policy-section">
                    <h3>사업자 정보</h3>
                    <ul>
                        <li>상호: [실제 서비스명/사업자명 입력 필요]</li>
                        <li>대표자: [실제 대표자명 입력 필요]</li>
                        <li>사업자등록번호: [실제 사업자등록번호 입력 필요]</li>
                        <li>주소: [실제 사업장 주소 입력 필요]</li>
                        <li>전화: [실제 연락처 입력 필요]</li>
                        <li>이메일: [실제 고객문의 이메일 입력 필요]</li>
                    </ul>
                </div>

                <div className="policy-section">
                    <h3>서비스의 목적</h3>
                    <p>
                        이 서비스는 사용자가 업로드한 발표 영상을 분석해 발표 코칭 피드백을 제공하는 것을
                        목적으로 합니다. 분석 결과는 참고용이며 평가, 채용, 의료, 법률 판단을 대체하지 않습니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>회원 계정</h3>
                    <p>
                        회원은 이메일과 비밀번호로 계정을 만들며, 자신의 계정 정보와 비밀번호를 안전하게
                        관리해야 합니다. 비밀번호는 서버에 평문으로 저장되지 않고 해시로 저장됩니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>영상 업로드와 외부 AI 처리 동의</h3>
                    <p>
                        회원은 발표 분석을 위해 자신의 영상이 서버에 업로드되고, 분석 과정에서 OpenAI 및
                        NVIDIA 같은 외부 AI API로 영상 또는 분석 데이터가 전송될 수 있다는 점에 동의합니다.
                        타인의 얼굴, 음성, 개인정보가 포함된 영상을 업로드할 때에는 필요한 권한과 동의를
                        확보해야 합니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>보관과 삭제</h3>
                    <p>
                        원본 영상은 분석 완료 후 기본 30일 동안 보관된 뒤 정리 대상이 됩니다. 사용자는 결과
                        삭제 또는 회원탈퇴 기능을 통해 자신의 분석 데이터 삭제를 요청할 수 있습니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>금지 행위</h3>
                    <p>
                        불법 촬영물, 권한 없이 수집한 영상, 악성 파일, 서비스 운영을 방해하는 요청, 타인의
                        권리를 침해하는 자료를 업로드해서는 안 됩니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>서비스 변경과 한계</h3>
                    <p>
                        AI 분석 결과는 모델과 입력 영상 품질에 따라 부정확할 수 있습니다. 서비스의 기능,
                        외부 AI 연동 방식, 보존 정책은 운영 과정에서 변경될 수 있으며, 실제 서비스 출시 전
                        최종 약관과 개인정보처리방침을 다시 확정해야 합니다.
                    </p>
                </div>

                <p className="policy-links">
                    <Link to="/privacy">개인정보처리방침 보기</Link>
                </p>
            </article>
        </section>
    );
}

export default TermsPage;
