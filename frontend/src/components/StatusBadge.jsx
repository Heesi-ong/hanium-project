const RUNNING_STATUSES = [
    "BASIC_ANALYZING",
    "VIDEO_LLM_ANALYZING",
    "COMPACTING",
    "OPENAI_GENERATING",
    "MERGING_RESULT",
];

function StatusBadge({ status, label }) {
    const className = getStatusClassName(status);

    return (
        <span className={className}>
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
