// 카드형 레이아웃과 강조 색상 variant를 제공하는 공통 UI 컴포넌트다.
import "./ui.css";

function Card({ accent = "", as: Component = "section", children, className = "", ...props }) {
  const classes = ["card", "ui-card", accent ? `ui-card-accent-${accent}` : "", className]
    .filter(Boolean)
    .join(" ");

  return (
    <Component className={classes} {...props}>
      {children}
    </Component>
  );
}

export default Card;
