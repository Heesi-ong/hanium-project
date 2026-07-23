import atexit
import logging
import queue
import threading
import urllib.request
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from mediapipe.tasks import python
from mediapipe.tasks.python import vision

from app.core.paths import resolve_project_root
from app.core.settings import AnalysisEngineSettings

logger = logging.getLogger("analysis-engine")

WHISPER_MODEL_SIZE = "base"
WHISPER_DEVICE = "cpu"
WHISPER_COMPUTE_TYPE = "int8"

POSE_TASK_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/"
    "pose_landmarker/pose_landmarker_lite/float16/latest/"
    "pose_landmarker_lite.task"
)

FACE_TASK_MODEL_URL = (
    "https://storage.googleapis.com/mediapipe-models/"
    "face_landmarker/face_landmarker/float16/latest/"
    "face_landmarker.task"
)

# 동시에 여러 분석 작업이 들어와도 모델 추론이 완전히 직렬화되지 않도록, 모델별로
# 미리 만들어둔 인스턴스 풀에서 빌려 쓰고 반납하는 방식을 사용합니다. 풀 크기만큼
# 동시 추론이 가능해지고, 그 이상 요청이 몰리면 인스턴스가 반납될 때까지 자연스럽게
# 대기합니다(단일 락과 달리 완전 직렬화는 아닙니다). 풀 크기는 컨테이너 CPU/메모리
# 제한에 맞게 환경변수로 조정하세요.
WHISPER_POOL_SIZE = 2
POSE_POOL_SIZE = 2
FACE_POOL_SIZE = 2

_whisper_pool: "queue.Queue[object]" = queue.Queue()
_pose_pool: "queue.Queue[object]" = queue.Queue()
_face_pool: "queue.Queue[object]" = queue.Queue()

_whisper_load_lock = threading.Lock()
_pose_load_lock = threading.Lock()
_face_load_lock = threading.Lock()

# 풀에 지금까지 만들어 넣은 인스턴스 개수입니다. 풀(Queue)이 일시적으로 비어 있는 것은
# "인스턴스가 전부 대여 중"인 정상 상태일 수 있으므로, model_status()는 풀 크기가 아니라
# 이 카운터로 "최소 1개는 로드됐는지"를 판단합니다.
_whisper_loaded_count = 0
_pose_loaded_count = 0
_face_loaded_count = 0


def configure_pool_sizes(settings: AnalysisEngineSettings) -> None:
    global WHISPER_POOL_SIZE, POSE_POOL_SIZE, FACE_POOL_SIZE

    if any((_whisper_loaded_count, _pose_loaded_count, _face_loaded_count)):
        raise RuntimeError("Model pool sizes cannot change after models have been loaded.")

    WHISPER_POOL_SIZE = settings.whisper_pool_size
    POSE_POOL_SIZE = settings.pose_pool_size
    FACE_POOL_SIZE = settings.face_pool_size


def preload_all() -> None:
    _ensure_whisper_pool()
    _ensure_pose_pool()
    _ensure_face_pool()
    logger.info(
        "모델 프리로딩 완료: whisper=%s(x%d), pose=loaded(x%d), face=loaded(x%d)",
        WHISPER_MODEL_SIZE,
        WHISPER_POOL_SIZE,
        POSE_POOL_SIZE,
        FACE_POOL_SIZE,
    )


def _create_whisper_instance():
    from faster_whisper import WhisperModel

    logger.info(
        "Whisper 모델 인스턴스를 로딩합니다. size=%s device=%s computeType=%s",
        WHISPER_MODEL_SIZE,
        WHISPER_DEVICE,
        WHISPER_COMPUTE_TYPE,
    )
    return WhisperModel(
        WHISPER_MODEL_SIZE,
        device=WHISPER_DEVICE,
        compute_type=WHISPER_COMPUTE_TYPE,
    )


def _ensure_whisper_pool() -> None:
    global _whisper_loaded_count

    if _whisper_loaded_count >= WHISPER_POOL_SIZE:
        return

    with _whisper_load_lock:
        while _whisper_loaded_count < WHISPER_POOL_SIZE:
            _whisper_pool.put(_create_whisper_instance())
            _whisper_loaded_count += 1


def _create_pose_instance():
    pose_model_path = get_pose_model_path()
    logger.info("PoseLandmarker 모델 인스턴스를 로딩합니다. path=%s", pose_model_path)
    return vision.PoseLandmarker.create_from_options(
        vision.PoseLandmarkerOptions(
            base_options=python.BaseOptions(
                model_asset_path=str(pose_model_path),
                delegate=python.BaseOptions.Delegate.CPU,
            ),
            running_mode=vision.RunningMode.IMAGE,
            num_poses=1,
            min_pose_detection_confidence=0.5,
            min_pose_presence_confidence=0.5,
            min_tracking_confidence=0.5,
        )
    )


def _ensure_pose_pool() -> None:
    global _pose_loaded_count

    if _pose_loaded_count >= POSE_POOL_SIZE:
        return

    with _pose_load_lock:
        while _pose_loaded_count < POSE_POOL_SIZE:
            _pose_pool.put(_create_pose_instance())
            _pose_loaded_count += 1


def _create_face_instance():
    face_model_path = get_face_model_path()
    logger.info("FaceLandmarker 모델 인스턴스를 로딩합니다. path=%s", face_model_path)
    return vision.FaceLandmarker.create_from_options(
        vision.FaceLandmarkerOptions(
            base_options=python.BaseOptions(
                model_asset_path=str(face_model_path),
                delegate=python.BaseOptions.Delegate.CPU,
            ),
            running_mode=vision.RunningMode.IMAGE,
            num_faces=1,
            min_face_detection_confidence=0.5,
            min_face_presence_confidence=0.5,
            min_tracking_confidence=0.5,
            output_face_blendshapes=False,
            output_facial_transformation_matrixes=False,
        )
    )


def _ensure_face_pool() -> None:
    global _face_loaded_count

    if _face_loaded_count >= FACE_POOL_SIZE:
        return

    with _face_load_lock:
        while _face_loaded_count < FACE_POOL_SIZE:
            _face_pool.put(_create_face_instance())
            _face_loaded_count += 1


@contextmanager
def whisper_model_context() -> Iterator[object]:
    _ensure_whisper_pool()
    model = _whisper_pool.get()
    try:
        yield model
    finally:
        _whisper_pool.put(model)


@contextmanager
def pose_landmarker_context() -> Iterator[object]:
    _ensure_pose_pool()
    landmarker = _pose_pool.get()
    try:
        yield landmarker
    finally:
        _pose_pool.put(landmarker)


@contextmanager
def face_landmarker_context() -> Iterator[object]:
    _ensure_face_pool()
    landmarker = _face_pool.get()
    try:
        yield landmarker
    finally:
        _face_pool.put(landmarker)


def resolve_model_directory() -> Path:
    project_root = resolve_project_root()
    model_directory = project_root / "storage" / "models" / "mediapipe"
    model_directory.mkdir(parents=True, exist_ok=True)
    return model_directory


def download_file_if_missing(
        url: str,
        file_path: Path,
) -> None:
    if file_path.exists() and file_path.stat().st_size > 0:
        return

    file_path.parent.mkdir(parents=True, exist_ok=True)
    logger.info("MediaPipe 모델 파일을 다운로드합니다. url=%s path=%s", url, file_path)
    urllib.request.urlretrieve(url, file_path)


def get_pose_model_path() -> Path:
    model_path = resolve_model_directory() / "pose_landmarker_lite.task"
    download_file_if_missing(POSE_TASK_MODEL_URL, model_path)
    return model_path


def get_face_model_path() -> Path:
    model_path = resolve_model_directory() / "face_landmarker.task"
    download_file_if_missing(FACE_TASK_MODEL_URL, model_path)
    return model_path


def model_status() -> dict[str, bool]:
    return {
        "whisper": _whisper_loaded_count > 0,
        "pose": _pose_loaded_count > 0,
        "face": _face_loaded_count > 0,
    }


def _close_pool(pool_name: str, pool: queue.Queue[object]) -> int:
    closed_count = 0

    while not pool.empty():
        try:
            instance = pool.get_nowait()
        except queue.Empty:
            break

        closed_count += 1
        if hasattr(instance, "close"):
            try:
                instance.close()
            except Exception:
                logger.warning("%s 모델 종료 중 오류가 발생했습니다.", pool_name, exc_info=True)

    return closed_count


def close_all() -> None:
    global _whisper_loaded_count, _pose_loaded_count, _face_loaded_count

    # FastAPI lifespan 종료 시점에는 대부분 요청 처리가 끝난 상태지만, graceful shutdown
    # 유예 시간 안에 끝나지 않은 요청이 model_context()로 인스턴스를 빌려 간(큐 밖에 있는)
    # 상태로 남아 있을 수 있습니다. 카운터를 무조건 0으로 리셋하면, 그 스레드가 나중에
    # finally에서 인스턴스를 반납했을 때 풀에는 실제로 인스턴스가 남아 있는데 카운터만
    # 0으로 남아 다음 _ensure_*_pool() 호출이 필요 이상으로 새 인스턴스를 더 만들어내고
    # POOL_SIZE보다 많은 인스턴스가 쌓이게 됩니다. 그래서 카운터는 "이번에 실제로 큐에서
    # 꺼내 닫은 개수"만큼만 뺍니다 - 대여 중이던 인스턴스는 이번 종료 대상에서 제외되고
    # (진행 중인 요청을 방해하지 않음) 나중에 반납되면 그대로 풀의 정상 멤버로 남습니다.
    with _whisper_load_lock:
        closed_count = _close_pool("whisper", _whisper_pool)
        _whisper_loaded_count = max(0, _whisper_loaded_count - closed_count)

    with _pose_load_lock:
        closed_count = _close_pool("pose", _pose_pool)
        _pose_loaded_count = max(0, _pose_loaded_count - closed_count)

    with _face_load_lock:
        closed_count = _close_pool("face", _face_pool)
        _face_loaded_count = max(0, _face_loaded_count - closed_count)


atexit.register(close_all)
