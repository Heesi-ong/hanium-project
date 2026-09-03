import { Link } from "react-router-dom";

import GuidePageShell from "../components/guides/GuidePageShell";
import { buttonVariantClassName } from "../components/ui/Button";

const SECTIONS = [
    {
        id: "test-data",
        title: "처리하는 테스트 데이터",
        content:
            "계정 테스트를 위한 이메일과 비밀번호 해시, 업로드한 발표 영상, 분석 job 상태, 정량 분석 결과, AI 피드백과 오류 로그를 로컬 테스트 환경에서 처리합니다. 비밀번호 평문은 저장하지 않습니다.",
    },
    {
        id: "processing-purpose",
        title: "처리 목적",
        content:
            "영상 업로드→비동기 분석→진행 상태→결과 조회 흐름을 검증하고, 자세·음성·제스처 분석과 발표 코칭 피드백을 시연하는 데 사용합니다.",
    },
    {
        id: "external-ai",
        title: "외부 AI 호출",
        content:
            "기본 로컬 설정에서 OpenAI 피드백과 Video LLM 실제 호출은 비활성화됩니다. 테스트 담당자가 명시적으로 활성화하면 영상에서 추출한 분석 요약이 OpenAI API로, 영상 파일 또는 asset 참조가 NVIDIA API로 전송될 수 있습니다. 외부 AI 테스트 전에 동의 여부와 사용할 데이터를 다시 확인하세요.",
    },
    {
        id: "retention-deletion",
        title: "보관과 삭제",
        content:
            "원본 영상은 분석 완료 후 기본 30일이 지나면 정리 대상이 됩니다. 사용자는 결과 삭제 또는 회원탈퇴로 자신의 job, 영상, 결과 삭제를 요청할 수 있습니다. 시연이 끝나면 테스트 계정과 영상을 직접 삭제하세요.",
    },
    {
        id: "backup-caution",
        title: "선택적 백업 주의",
        content:
            "기본 `docker compose up`은 자동 백업을 실행하지 않습니다. 테스트 담당자가 `ops` 프로필로 백업을 직접 실행했다면 결과 삭제나 회원탈퇴 후에도 백업 파일을 별도로 정리해야 합니다.",
    },
    {
        id: "logs",
        title: "로그",
        content:
            "분석 진행과 오류 확인을 위해 requestId, jobId, 분석 단계가 로그에 기록됩니다. 영상 내용, 비밀번호, 인증 토큰을 로그에 직접 남기지 마세요. 로컬 파일 로그는 현재 설정에서 최대 30일 범위로 순환됩니다.",
    },
];

function PrivacyPage() {
    return (
        <GuidePageShell
            eyebrow="Student Project Data Guide"
            title="테스트 데이터 처리 안내"
            notice="이 프로젝트는 공개 온라인 서비스가 아닌 로컬/통제된 테스트 시연용입니다. 실제 사람의 얼굴·음성·개인정보가 포함된 영상은 반드시 본인과 찍힌 사람의 명시적 동의를 받은 경우에만 사용하세요."
            sections={SECTIONS}
            actions={(
                <Link
                    to="/terms"
                    className={buttonVariantClassName("secondary", "w-full sm:w-auto")}
                >
                    프로젝트 이용 안내 보기
                </Link>
            )}
        />
    );
}

export default PrivacyPage;
