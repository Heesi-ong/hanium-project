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


@pytest.mark.parametrize("value", ["0", "-1", "not-a-number"])
def test_pool_size_must_be_a_positive_integer(monkeypatch, value):
    monkeypatch.setenv("TEST_MODEL_POOL_SIZE", value)

    with pytest.raises(ValueError, match="TEST_MODEL_POOL_SIZE"):
        model_registry._positive_int_from_env("TEST_MODEL_POOL_SIZE", 2)


def test_pool_size_uses_default_and_accepts_positive_value(monkeypatch):
    monkeypatch.delenv("TEST_MODEL_POOL_SIZE", raising=False)
    assert model_registry._positive_int_from_env("TEST_MODEL_POOL_SIZE", 3) == 3

    monkeypatch.setenv("TEST_MODEL_POOL_SIZE", "4")
    assert model_registry._positive_int_from_env("TEST_MODEL_POOL_SIZE", 3) == 4


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


def test_close_all_closes_instances_and_resets_readiness(monkeypatch):
    class ClosableInstance:
        def __init__(self):
            self.closed = False

        def close(self):
            self.closed = True

    whisper_instances = []
    pose_instances = []
    face_instances = []

    def create_whisper_instance():
        instance = ClosableInstance()
        whisper_instances.append(instance)
        return instance

    def create_pose_instance():
        instance = ClosableInstance()
        pose_instances.append(instance)
        return instance

    def create_face_instance():
        instance = ClosableInstance()
        face_instances.append(instance)
        return instance

    monkeypatch.setattr(model_registry, "_create_whisper_instance", create_whisper_instance)
    monkeypatch.setattr(model_registry, "_create_pose_instance", create_pose_instance)
    monkeypatch.setattr(model_registry, "_create_face_instance", create_face_instance)

    model_registry._ensure_whisper_pool()
    model_registry._ensure_pose_pool()
    model_registry._ensure_face_pool()
    model_registry.close_all()

    assert all(instance.closed for instance in whisper_instances)
    assert all(instance.closed for instance in pose_instances)
    assert all(instance.closed for instance in face_instances)
    assert model_registry._whisper_pool.empty()
    assert model_registry._pose_pool.empty()
    assert model_registry._face_pool.empty()
    assert model_registry.model_status() == {
        "whisper": False,
        "pose": False,
        "face": False,
    }
