// 프로젝트 전반에서 쓰는 버튼 variant와 비활성/로딩 상태 스타일을 제공한다.
import "./ui.css";

const variantClassName = {
  primary: "",
  secondary: "secondary",
  danger: "danger",
  ghost: "ghost",
};

function Button({
  block = false,
  children,
  className = "",
  size = "md",
  type = "button",
  variant = "primary",
  ...props
}) {
  const classes = [
    "button",
    "ui-button",
    variantClassName[variant] || "",
    size !== "md" ? `ui-button-${size}` : "",
    block ? "ui-button-block" : "",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <button className={classes} type={type} {...props}>
      {children}
    </button>
  );
}

export default Button;
