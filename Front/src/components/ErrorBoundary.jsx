import React from "react";

import StateMessage from "./StateMessage";

export default class ErrorBoundary extends React.Component {
  constructor(props) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError() {
    return { hasError: true };
  }

  componentDidCatch(error, info) {
    console.error("Unexpected UI error", error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        <main className="page-shell">
          <StateMessage
            type="error"
            title="화면을 표시하지 못했습니다."
            actions={
              <button className="button" onClick={() => window.location.reload()}>
                페이지 다시 불러오기
              </button>
            }
          >
            잠시 후 다시 시도해주세요. 반복되면 관리자에게 문의해주세요.
          </StateMessage>
        </main>
      );
    }

    return this.props.children;
  }
}
