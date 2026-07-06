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

    const liveRegionProps = ["error", "failure"].includes(type)
        ? { role: "alert" }
        : { role: "status", "aria-live": "polite" };

    return (
        <div className={className} {...liveRegionProps}>
            {children}
        </div>
    );
}

export default StateMessage;
