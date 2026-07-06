import { Component } from "react";

// 화면을 그리는 도중(렌더링 중) 예상치 못한 오류가 나면, React는 기본적으로 화면 전체를
// 하얗게 지워버립니다. ErrorBoundary는 그 오류를 대신 잡아서 "오류가 났습니다" 같은
// 안내 화면을 보여주는 안전장치입니다. API 호출 실패는 각 페이지에서 이미 처리하고
// 있으므로, 이 컴포넌트는 그 외의 예상 못한 렌더링 오류(예: 응답 데이터 형식이 갑자기
// 달라져서 화면 코드가 깨지는 경우)를 막기 위한 최후의 방어선입니다.
class ErrorBoundary extends Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false };
    }

    static getDerivedStateFromError() {
        return { hasError: true };
    }

    componentDidCatch(error, errorInfo) {
        // 운영 환경에서는 이 자리에 별도 로그 수집 연동을 붙일 수 있습니다.
        // 지금은 최소한으로 콘솔에만 남깁니다.
        console.error("화면 렌더링 중 오류가 발생했습니다.", error, errorInfo);
    }

    handleReload = () => {
        this.setState({ hasError: false });
        window.location.reload();
    };

    render() {
        if (this.state.hasError) {
            return (
                <div role="alert" style={{ padding: "40px 20px", textAlign: "center" }}>
                    <p className="error-message">
                        화면을 표시하는 중 문제가 발생했습니다. 잠시 후 다시 시도해 주세요.
                    </p>
                    <button type="button" className="primary-button" onClick={this.handleReload}>
                        새로고침
                    </button>
                </div>
            );
        }

        return this.props.children;
    }
}

export default ErrorBoundary;
