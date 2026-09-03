function PasswordToggleButton({ visible, onToggle, disabled = false }) {
    return (
        <button
            type="button"
            className="password-toggle-button min-h-11 rounded-lg px-1 disabled:cursor-not-allowed disabled:opacity-50"
            aria-pressed={visible}
            disabled={disabled}
            onClick={onToggle}
        >
            {visible ? "비밀번호 숨기기" : "비밀번호 표시"}
        </button>
    );
}

export default PasswordToggleButton;
