function Spinner({ size = 32 }) {
    return (
        <div
            className="spinner"
            style={{ width: size, height: size }}
            role="status"
            aria-label="로딩 중"
        />
    );
}

export default Spinner;
