import AppRoutes from "./routes/AppRoutes";
import ErrorBoundary from "./components/common/ErrorBoundary";
import ToastContainer from "./components/ToastContainer";
import "./App.css";

function App() {
  return (
    <ErrorBoundary>
      <AppRoutes />
      <ToastContainer />
    </ErrorBoundary>
  );
}

export default App;
