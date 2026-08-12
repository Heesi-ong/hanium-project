// 약관/개인정보처리방침의 사업자 정보는 지금 실제 값이 없어 placeholder를 그대로
// 노출하고 있다(P0-02, 2026-08-03 서비스화 점검). 실제 정보가 준비되면 코드를 다시
// 고치는 대신 빌드 시 환경변수만 채우면 되도록, 값을 이 한 곳에서만 해석한다
// (PrivacyPage.jsx/TermsPage.jsx 양쪽이 같은 값을 쓴다). 값이 비어 있으면 지금까지와
// 동일한 placeholder 문구를 그대로 보여준다 — 이 변경 자체는 화면 동작을 바꾸지 않는다.
function resolve(envValue, placeholder) {
    return envValue && envValue.trim() ? envValue.trim() : placeholder;
}

export const BUSINESS_NAME = resolve(
    import.meta.env.VITE_BUSINESS_NAME,
    "[실제 서비스명/사업자명 입력 필요]"
);

export const BUSINESS_REPRESENTATIVE = resolve(
    import.meta.env.VITE_BUSINESS_REPRESENTATIVE,
    "[실제 대표자명 입력 필요]"
);

export const BUSINESS_REGISTRATION_NUMBER = resolve(
    import.meta.env.VITE_BUSINESS_REGISTRATION_NUMBER,
    "[실제 사업자등록번호 입력 필요]"
);

export const BUSINESS_ADDRESS = resolve(
    import.meta.env.VITE_BUSINESS_ADDRESS,
    "[실제 사업장 주소 입력 필요]"
);

export const BUSINESS_PHONE = resolve(
    import.meta.env.VITE_BUSINESS_PHONE,
    "[실제 연락처 입력 필요]"
);

export const BUSINESS_EMAIL = resolve(
    import.meta.env.VITE_BUSINESS_EMAIL,
    "[실제 고객문의 이메일 입력 필요]"
);

export const PRIVACY_OFFICER_NAME = resolve(
    import.meta.env.VITE_PRIVACY_OFFICER_NAME,
    "[실제 개인정보 보호책임자 이름 또는 직책 입력 필요]"
);

export const PRIVACY_OFFICER_EMAIL = resolve(
    import.meta.env.VITE_PRIVACY_OFFICER_EMAIL,
    "[실제 개인정보 문의 이메일 입력 필요]"
);

export const PRIVACY_OFFICER_PHONE = resolve(
    import.meta.env.VITE_PRIVACY_OFFICER_PHONE,
    "[실제 개인정보 문의 전화번호 입력 필요]"
);
