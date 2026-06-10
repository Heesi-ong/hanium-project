import React from "react";

import "./StateMessage.css";

export default function StateMessage({ type = "info", title, children, actions, compact = false }) {
  return (
    <div
      className={`state-message state-message-${type}${compact ? " compact" : ""}`}
      role={type === "error" ? "alert" : "status"}
    >
      <div className="state-message-mark" aria-hidden="true" />
      <div className="state-message-content">
        {title && <h2>{title}</h2>}
        {children && <div className="state-message-body">{children}</div>}
        {actions && <div className="state-message-actions">{actions}</div>}
      </div>
    </div>
  );
}
