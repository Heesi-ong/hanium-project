import { Link } from "react-router-dom";

import GuidePageShell from "../components/guides/GuidePageShell";
import { buttonVariantClassName } from "../components/ui/Button";

const SECTIONS = [
    {
        id: "project-purpose",
        title: "프로젝트 목적",
        content:
            "사용자가 업로드한 발표 영상을 분석해 연습용 코칭 피드백을 제공하고, 비동기 분석 파이프라인과 결과 UI를 검증하는 것이 목적입니다. 분석 결과는 채용·학점·의료·심리 판단을 대체하지 않습니다.",
    },
    {
        id: "test-account",
        title: "테스트 계정",
        content:
            "계정은 로컬 기능 검증에만 사용하고 다른 서비스와 같은 비밀번호를 재사용하지 마세요. 시연 후에는 계정 설정에서 탈퇴하여 테스트 데이터를 정리하는 것을 권장합니다.",
    },
    {
        id: "video-consent",
        title: "영상 사용과 외부 AI 동의",
        content:
            "본인이 촬영했거나 필요한 권한과 동의를 확보한 영상만 업로드하세요. OpenAI 또는 NVIDIA 외부 AI 호출을 활성화한 테스트에서는 영상 또는 분석 요약이 외부 API로 전송될 수 있습니다. 동의하지 않는 경우 외부 AI 기능을 비활성화하고 mock 모드로만 테스트하세요.",
    },
    {
        id: "retention-deletion",
        title: "보관과 삭제",
        content:
            "원본 영상은 분석 완료 후 기본 30일이 지나면 정리 대상이 됩니다. 결과 삭제와 회원탈퇴 기능으로 job, 영상, 결과 삭제를 요청할 수 있습니다.",
    },
    {
        id: "prohibited-use",
        title: "금지 행위",
        content:
            "불법 촬영물, 권한 없이 수집한 영상, 신분증·주소·전화번호 등 불필요한 민감정보가 드러난 영상, 악성 파일, 타인의 권리를 침해하는 자료를 업로드하지 마세요.",
    },
    {
        id: "test-limitations",
        title: "테스트 한계",
        content:
            "영상 품질, 촬영 각도, 음성 품질, 모델 상태에 따라 결과가 부정확할 수 있습니다. 기능과 데이터 구조는 프로젝트 개발 과정에서 변경될 수 있습니다.",
    },
];

function TermsPage() {
    return (
        <GuidePageShell
            eyebrow="Student Project Guide"
            title="프로젝트 이용 안내"
            notice="이 안내는 학생 프로젝트의 로컬 테스트 규칙으로, 공개 상용 서비스의 이용약관이 아닙니다. 통제된 시연 환경에서만 사용하세요."
            sections={SECTIONS}
            actions={(
                <Link
                    to="/privacy"
                    className={buttonVariantClassName("secondary", "w-full sm:w-auto")}
                >
                    테스트 데이터 처리 안내 보기
                </Link>
            )}
        />
    );
}

export default TermsPage;
