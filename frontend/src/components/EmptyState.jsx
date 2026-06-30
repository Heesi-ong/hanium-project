function EmptyState({ title, description }) {
    return (
        <div className="empty-state">
            {title && <p>{title}</p>}
            {description && <p>{description}</p>}
        </div>
    );
}

export default EmptyState;