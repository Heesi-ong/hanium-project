import {
    BUSINESS_ADDRESS,
    BUSINESS_EMAIL,
    BUSINESS_NAME,
    BUSINESS_PHONE,
    BUSINESS_REGISTRATION_NUMBER,
    BUSINESS_REPRESENTATIVE,
} from "../constants/businessInfo";

// PrivacyPage.jsx와 TermsPage.jsx가 완전히 동일한 "사업자 정보" 블록을 각자 갖고
// 있었다. 값 자체는 constants/businessInfo.js에서 해석하고, 이 컴포넌트는 마크업만
// 공유한다.
function BusinessInfoSection() {
    return (
        <div className="policy-section">
            <h3>사업자 정보</h3>
            <ul>
                <li>상호: {BUSINESS_NAME}</li>
                <li>대표자: {BUSINESS_REPRESENTATIVE}</li>
                <li>사업자등록번호: {BUSINESS_REGISTRATION_NUMBER}</li>
                <li>주소: {BUSINESS_ADDRESS}</li>
                <li>전화: {BUSINESS_PHONE}</li>
                <li>이메일: {BUSINESS_EMAIL}</li>
            </ul>
        </div>
    );
}

export default BusinessInfoSection;
