function StateMessage({ type = "info", children }) {
    if (!children) {
        return null;
    }

    const className = {
        error: "error-message",
        success: "success-text",
        polling: "polling-text",
        failure: "failure-text",
        info: "polling-text",
    }[type] || "polling-text";

    return <div className={className}>{children}</div>;
}

export default StateMessage;