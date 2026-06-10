import React from "react";
import { useEffect, useRef, useState } from "react";

export default function ActionDialog({
  open,
  title,
  description,
  confirmLabel = "확인",
  danger = false,
  initialValue,
  onCancel,
  onConfirm,
}) {
  const confirmRef = useRef(null);
  const [value, setValue] = useState(initialValue || "");
  const hasInput = initialValue !== undefined;

  useEffect(() => {
    if (!open) return;
    setValue(initialValue || "");
    confirmRef.current?.focus();
  }, [initialValue, open]);

  if (!open) return null;

  return (
    <div
      className="dialog-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onCancel();
      }}
    >
      <section
        className="dialog-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby="action-dialog-title"
        aria-describedby={description ? "action-dialog-description" : undefined}
        onKeyDown={(event) => {
          if (event.key === "Escape") onCancel();
        }}
      >
        <h2 id="action-dialog-title">{title}</h2>
        {description && <p id="action-dialog-description">{description}</p>}
        {hasInput && (
          <input
            aria-label={title}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            autoFocus
          />
        )}
        <div className="dialog-actions">
          <button className="button secondary" onClick={onCancel}>
            취소
          </button>
          <button
            ref={confirmRef}
            className={`button${danger ? " danger" : ""}`}
            disabled={hasInput && !value.trim()}
            onClick={() => onConfirm(hasInput ? value.trim() : undefined)}
          >
            {confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
}
