function PageHeader({ eyebrow, title, description }) {
    return (
        <div className="page-header">
            {eyebrow && <p className="eyebrow">{eyebrow}</p>}
            <h1>{title}</h1>
            {description && <p>{description}</p>}
        </div>
    );
}

export default PageHeader;