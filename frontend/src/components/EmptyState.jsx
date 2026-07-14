import Spinner from "./Spinner";

function EmptyState({ title, description, loading = false }) {
    return (
        <div className="empty-state">
            {loading && <Spinner />}
            {title && <p>{title}</p>}
            {description && <p>{description}</p>}
        </div>
    );
}

export default EmptyState;
