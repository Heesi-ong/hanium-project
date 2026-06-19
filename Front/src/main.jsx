// React 앱을 DOM에 마운트하고 전역 오류 경계를 적용하는 프론트엔드 진입점이다.
import React from "react";
import ReactDOM from "react-dom/client";

import App from "./App.jsx";
import ErrorBoundary from "./components/ErrorBoundary.jsx";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </React.StrictMode>,
);
