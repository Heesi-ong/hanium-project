import { Link } from "react-router-dom";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";

function NotFoundPage() {
    return (
        <section className="page-section">
            <PageHeader
                eyebrow="Not Found"
                title="페이지를 찾을 수 없습니다"
                description="요청하신 주소가 없거나 이동된 페이지입니다."
            />

            <EmptyState
                title="잘못된 주소로 접근했습니다."
                description="홈으로 돌아가 다시 필요한 메뉴를 선택해주세요."
            />

            <div className="button-row">
                <Link to="/" className="primary-button">
                    홈으로 돌아가기
                </Link>
            </div>
        </section>
    );
}

export default NotFoundPage;
