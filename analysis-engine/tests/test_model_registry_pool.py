import queue

import pytest

from app.core import model_registry


def drain_pool(pool: queue.Queue) -> None:
    while True:
        try:
            pool.get_nowait()
        except queue.Empty:
            return


@pytest.fixture(autouse=True)
def reset_model_pools(monkeypatch):
    pools = (
        model_registry._whisper_pool,
        model_registry._pose_pool,
        model_registry._face_pool,
    )

    for pool in pools:
        drain_pool(pool)

    monkeypatch.setattr(model_registry, "WHISPER_POOL_SIZE", 2)
    monkeypatch.setattr(model_registry, "POSE_POOL_SIZE", 2)
    monkeypatch.setattr(model_registry, "FACE_POOL_SIZE", 2)
    monkeypatch.setattr(model_registry, "_whisper_loaded_count", 0)
    monkeypatch.setattr(model_registry, "_pose_loaded_count", 0)
    monkeypatch.setattr(model_registry, "_face_loaded_count", 0)

    yield

    for pool in pools:
        drain_pool(pool)


def test_ensure_whisper_pool_does_not_create_duplicate_instances(monkeypatch):
    created_instances = []

    def create_instance():
        instance = object()
        created_instances.append(instance)
        return instance

    monkeypatch.setattr(model_registry, "_create_whisper_instance", create_instance)

    model_registry._ensure_whisper_pool()
    model_registry._ensure_whisper_pool()

    assert model_registry._whisper_loaded_count == model_registry.WHISPER_POOL_SIZE
    assert model_registry._whisper_pool.qsize() == model_registry.WHISPER_POOL_SIZE
    assert len(created_instances) == model_registry.WHISPER_POOL_SIZE


def test_nested_whisper_contexts_borrow_different_instances(monkeypatch):
    monkeypatch.setattr(model_registry, "_create_whisper_instance", object)

    with model_registry.whisper_model_context() as first_model:
        with model_registry.whisper_model_context() as second_model:
            assert first_model is not second_model


def test_whisper_context_returns_instance_to_pool(monkeypatch):
    monkeypatch.setattr(model_registry, "_create_whisper_instance", object)

    with model_registry.whisper_model_context() as borrowed_model:
        assert model_registry._whisper_pool.qsize() == model_registry.WHISPER_POOL_SIZE - 1

    returned_instances = []
    while not model_registry._whisper_pool.empty():
        returned_instances.append(model_registry._whisper_pool.get_nowait())

    assert borrowed_model in returned_instances
    assert len(returned_instances) == model_registry.WHISPER_POOL_SIZE


def test_model_status_stays_loaded_while_all_instances_are_borrowed(monkeypatch):
    monkeypatch.setattr(model_registry, "_create_whisper_instance", object)

    with model_registry.whisper_model_context():
        with model_registry.whisper_model_context():
            assert model_registry._whisper_pool.empty()
            assert model_registry.model_status()["whisper"] is True


def test_close_all_closes_every_pose_and_face_instance(monkeypatch):
    class ClosableInstance:
        def __init__(self):
            self.closed = False

        def close(self):
            self.closed = True

    pose_instances = []
    face_instances = []

    def create_pose_instance():
        instance = ClosableInstance()
        pose_instances.append(instance)
        return instance

    def create_face_instance():
        instance = ClosableInstance()
        face_instances.append(instance)
        return instance

    monkeypatch.setattr(model_registry, "_create_pose_instance", create_pose_instance)
    monkeypatch.setattr(model_registry, "_create_face_instance", create_face_instance)

    model_registry._ensure_pose_pool()
    model_registry._ensure_face_pool()
    model_registry.close_all()

    assert all(instance.closed for instance in pose_instances)
    assert all(instance.closed for instance in face_instances)
    assert model_registry._pose_pool.empty()
    assert model_registry._face_pool.empty()
