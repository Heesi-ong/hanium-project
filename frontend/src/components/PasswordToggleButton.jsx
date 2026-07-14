function PasswordToggleButton({ visible, onToggle }) {
    return (
        <button
            type="button"
            className="password-toggle-button"
            onClick={onToggle}
        >
            {visible ? "비밀번호 숨기기" : "비밀번호 표시"}
        </button>
    );
}

export default PasswordToggleButton;
