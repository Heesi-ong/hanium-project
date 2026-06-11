import React from "react";
import { useEffect, useId, useRef, useState } from "react";

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
  const dialogRef = useRef(null);
  const previousFocusRef = useRef(null);
  const titleId = useId();
  const descriptionId = useId();
  const [value, setValue] = useState(initialValue || "");
  const hasInput = initialValue !== undefined;

  useEffect(() => {
    if (!open) return;
    previousFocusRef.current = document.activeElement;
    setValue(initialValue || "");
    confirmRef.current?.focus();
    return () => previousFocusRef.current?.focus();
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
        ref={dialogRef}
        className="dialog-card"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        onKeyDown={(event) => {
          if (event.key === "Escape") onCancel();
          if (event.key === "Tab") {
            const focusable = dialogRef.current?.querySelectorAll(
              'button:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])',
            );
            if (!focusable?.length) return;
            const first = focusable[0];
            const last = focusable[focusable.length - 1];
            if (event.shiftKey && document.activeElement === first) {
              event.preventDefault();
              last.focus();
            } else if (!event.shiftKey && document.activeElement === last) {
              event.preventDefault();
              first.focus();
            }
          }
        }}
      >
        <h2 id={titleId}>{title}</h2>
        {description && <p id={descriptionId}>{description}</p>}
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
