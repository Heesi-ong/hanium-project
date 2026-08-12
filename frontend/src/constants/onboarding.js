// OnboardingPage(최초 입력/수정)와 AccountPage(현재 값 표시)가 같은 옵션 목록과
// 라벨을 공유해야 값이 어긋나지 않는다.
export const PURPOSE_OPTIONS = [
    { value: "INTERVIEW", label: "면접 준비" },
    { value: "PRESENTATION", label: "발표/프레젠테이션" },
    { value: "LECTURE", label: "강의/교육" },
    { value: "OTHER", label: "기타" },
];

export const EXPERIENCE_LEVEL_OPTIONS = [
    { value: "BEGINNER", label: "입문" },
    { value: "INTERMEDIATE", label: "중급" },
    { value: "ADVANCED", label: "숙련" },
];

export const IMPROVEMENT_GOAL_OPTIONS = [
    { value: "VOICE_TONE", label: "목소리 톤" },
    { value: "PACE", label: "말하기 속도" },
    { value: "EYE_CONTACT", label: "시선 처리" },
    { value: "POSTURE", label: "자세" },
    { value: "CONTENT_STRUCTURE", label: "내용 구성" },
    { value: "OTHER", label: "기타" },
];

function toLabelMap(options) {
    return Object.fromEntries(options.map((option) => [option.value, option.label]));
}

const PURPOSE_LABELS = toLabelMap(PURPOSE_OPTIONS);
const EXPERIENCE_LEVEL_LABELS = toLabelMap(EXPERIENCE_LEVEL_OPTIONS);
const IMPROVEMENT_GOAL_LABELS = toLabelMap(IMPROVEMENT_GOAL_OPTIONS);

export function getPurposeLabel(value) {
    return PURPOSE_LABELS[value] || value;
}

export function getExperienceLevelLabel(value) {
    return EXPERIENCE_LEVEL_LABELS[value] || value;
}

export function getImprovementGoalLabel(value) {
    return IMPROVEMENT_GOAL_LABELS[value] || value;
}
