import Spinner from "./Spinner";

function EmptyState({ title, description, loading = false }) {
    return (
        <div className="empty-state">
            {loading && <Spinner />}
            {title && <p className="empty-state-title">{title}</p>}
            {description && <p className="empty-state-description">{description}</p>}
        </div>
    );
}

export default EmptyState;
