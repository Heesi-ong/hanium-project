import { Link } from "react-router-dom";

import GuidePageShell from "../components/guides/GuidePageShell";
import { buttonVariantClassName } from "../components/ui/Button";

const SECTIONS = [
    {
        id: "test-scope",
        title: "테스트 범위",
        content:
            "계정 생성, 영상 업로드, 비동기 분석 진행률, 결과 조회, AI 코치 대화, 재시도·취소·삭제 흐름을 로컬 환경에서 확인합니다.",
    },
    {
        id: "analysis-mode",
        title: "분석 모드",
        content:
            "정량 분석 엔진은 OpenCV, MediaPipe, faster-whisper를 사용합니다. OpenAI와 NVIDIA Video LLM은 환경변수와 테스트 키를 명시했을 때만 호출하며, 비활성 상태의 mock/fallback 결과와 실제 호출 결과를 구분해 표시합니다.",
    },
    {
        id: "demo-checklist",
        title: "시연 전 확인",
        bullets: [
            "네 애플리케이션 서비스와 MySQL, Redis, MinIO 상태를 확인합니다.",
            "테스트 영상의 사용 동의와 외부 AI 활성 여부를 확인합니다.",
            "터미널에서 분석 단계 로그를 추적하고 시연 후 테스트 데이터를 삭제합니다.",
        ],
    },
];

function PricingPage() {
    return (
        <GuidePageShell
            eyebrow="Local Test"
            title="프로젝트 테스트 안내"
            notice="이 프로젝트는 결제·요금제·공개 회원 모집 없이 로컬에서 기능을 검증하는 학생 프로젝트입니다."
            sections={SECTIONS}
            actions={(
                <>
                    <Link
                        to="/upload"
                        className={buttonVariantClassName("primary", "w-full sm:w-auto")}
                    >
                        로컬 테스트 시작하기
                    </Link>
                    <Link
                        to="/privacy"
                        className={buttonVariantClassName("secondary", "w-full sm:w-auto")}
                    >
                        데이터 처리 안내 보기
                    </Link>
                </>
            )}
        />
    );
}

export default PricingPage;
