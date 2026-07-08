const RUNNING_STATUSES = [
    "QUEUED",
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
];

function StatusBadge({ status, label }) {
    const className = getStatusClassName(status);
    const accessibleLabel = label || status || "상태 없음";

    return (
        <span className={className} role="status" aria-label={accessibleLabel}>
            {label || status || "-"}
        </span>
    );
}

function getStatusClassName(status) {
    if (status === "COMPLETED") {
        return "status-badge completed";
    }

    if (status === "FAILED") {
        return "status-badge failed";
    }

    if (status === "CANCELLED") {
        return "status-badge cancelled";
    }

    if (RUNNING_STATUSES.includes(status)) {
        return "status-badge running";
    }

    if (status === "UPLOADED") {
        return "status-badge uploaded";
    }

    return "status-badge uploaded";
}

export default StatusBadge;
