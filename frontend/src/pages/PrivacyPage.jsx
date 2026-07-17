import { Link } from "react-router-dom";

function PrivacyPage() {
    return (
        <section className="page-section">
            <article className="upload-card policy-card">
                <p className="eyebrow">Privacy Policy Draft</p>
                <h1>개인정보처리방침</h1>

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
                    <h3>개인정보 보호책임자</h3>
                    <ul>
                        <li>이름 또는 직책: [실제 개인정보 보호책임자 이름 또는 직책 입력 필요]</li>
                        <li>이메일: [실제 개인정보 문의 이메일 입력 필요]</li>
                        <li>전화: [실제 개인정보 문의 전화번호 입력 필요]</li>
                    </ul>
                </div>

                <div className="policy-section">
                    <h3>수집하는 정보</h3>
                    <p>
                        회원가입과 서비스 이용 과정에서 이메일, 비밀번호 해시, 업로드한 발표 영상 파일,
                        분석 작업 상태, 분석 결과와 AI 피드백을 처리합니다. 비밀번호는 평문으로 저장하지 않고
                        서버에서 해시 값으로 저장합니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>이용 목적</h3>
                    <p>
                        수집한 정보는 계정 생성과 로그인, 발표 영상 업로드, 자세·시선·음성·표정·제스처 분석,
                        AI 기반 발표 코칭 피드백 생성, 결과 조회와 삭제 기능 제공에 사용됩니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>외부 AI 처리</h3>
                    <p>
                        분석 기능을 제공하기 위해 업로드한 영상, 영상에서 추출되거나 압축된 분석 데이터,
                        결과 요약이 OpenAI 및 NVIDIA API로 전송될 수 있습니다. OpenAI는 종합 피드백 생성에,
                        NVIDIA는 Video LLM 기반 시각 분석에 사용될 수 있습니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>국외 이전에 관한 사항</h3>
                    <p>
                        발표 코칭 분석 기능을 제공하기 위해 아래와 같이 국외 사업자의 API로 데이터가
                        전송될 수 있습니다. OpenAI 연동은 서버에서 생성한
                        <code> compactAnalysis </code>
                        기반 피드백 요청을 전송하고, NVIDIA 연동은 영상 파일을 base64 data URL 또는
                        NVCF asset 참조 방식으로 전송하는 현재 구현을 기준으로 작성했습니다.
                    </p>
                    <ul>
                        <li>
                            OpenAI: 이전받는 자는 OpenAI, L.L.C. 및 관련 계열사입니다. 이전 항목은
                            작업 식별자, 영상에서 추출·압축된 분석 데이터, 점수 요약, 음성·시각·전사
                            요약, 피드백 생성에 필요한 입력입니다. 이전 국가는 미국 및 OpenAI의
                            계열사·파트너·서비스 제공자 소재 국가입니다. 이전 목적은 종합 발표 피드백
                            생성이며, 이전 방법은 OpenAI Responses API 호출입니다. 보유·이용 기간은
                            OpenAI API 정책 및 계약에 따르며, OpenAI는 일반 API 입력과 출력을 서비스
                            제공과 남용 탐지를 위해 최대 30일 보관할 수 있다고 안내합니다.
                        </li>
                        <li>
                            NVIDIA: 이전받는 자는 NVIDIA Corporation 및 관련 계열사입니다. 이전 항목은
                            업로드한 발표 영상 파일, 작업 식별자, 영상 길이·샘플링 힌트, 시각 분석
                            프롬프트입니다. 이전 국가는 미국 및 NVIDIA의 계열사·처리 인프라 소재
                            국가입니다. 이전 목적은 Video LLM 기반 시선·표정·제스처·자세 분석이며,
                            이전 방법은 NVIDIA chat completions API 호출 및 필요한 경우 NVCF Asset API
                            업로드입니다. 이 프로젝트는 NVCF asset을 요청 종료 후 삭제하도록 시도하지만,
                            NVIDIA 측 보유·이용 기간은 NVIDIA 정책 및 계약에 따릅니다.
                        </li>
                    </ul>
                </div>

                <div className="policy-section">
                    <h3>보관 기간과 삭제</h3>
                    <p>
                        원본 영상은 분석 완료 후 기본 30일 동안 보관되며, 설정값
                        <code> STORAGE_RETENTION_ORIGINAL_VIDEO_DAYS </code>
                        로 조정될 수 있습니다. 보존 기간이 지난 완료 작업의 원본 영상 업로드 디렉터리는 매일
                        새벽 3시 기본 스케줄로 정리됩니다. 분석 결과는 사용자가 결과를 삭제하거나 회원탈퇴를
                        요청할 때 삭제됩니다.
                    </p>
                </div>

                <div className="policy-section">
                    <h3>회원탈퇴와 데이터 삭제</h3>
                    <p>
                        사용자는 계정 설정에서 비밀번호를 재확인한 뒤 회원탈퇴를 요청할 수 있습니다.
                        회원탈퇴가 처리되면 계정과 사용자가 소유한 분석 작업, 업로드 영상, 결과 파일 삭제를
                        시도하며, 일부 분석 데이터 삭제에 실패하면 탈퇴 처리가 중단될 수 있습니다.
                    </p>
                </div>

                <p className="policy-links">
                    <Link to="/terms">이용약관 보기</Link>
                </p>
            </article>
        </section>
    );
}

export default PrivacyPage;
