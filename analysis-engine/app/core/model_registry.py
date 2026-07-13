import atexit
import logging
import threading
import urllib.request
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from mediapipe.tasks import python
from mediapipe.tasks.python import vision

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

_whisper_model = None
_pose_landmarker = None
_face_landmarker = None

_whisper_load_lock = threading.Lock()
_pose_load_lock = threading.Lock()
_face_load_lock = threading.Lock()

_whisper_inference_lock = threading.Lock()
_pose_inference_lock = threading.Lock()
_face_inference_lock = threading.Lock()


def preload_all() -> None:
    get_whisper_model()
    get_pose_landmarker()
    get_face_landmarker()
    logger.info("모델 프리로딩 완료: whisper=%s, pose=loaded, face=loaded", WHISPER_MODEL_SIZE)


def get_whisper_model():
    global _whisper_model

    if _whisper_model is None:
        with _whisper_load_lock:
            if _whisper_model is None:
                from faster_whisper import WhisperModel

                logger.info(
                    "Whisper 모델을 로딩합니다. size=%s device=%s computeType=%s",
                    WHISPER_MODEL_SIZE,
                    WHISPER_DEVICE,
                    WHISPER_COMPUTE_TYPE,
                )
                _whisper_model = WhisperModel(
                    WHISPER_MODEL_SIZE,
                    device=WHISPER_DEVICE,
                    compute_type=WHISPER_COMPUTE_TYPE,
                )

    return _whisper_model


def get_pose_landmarker():
    global _pose_landmarker

    if _pose_landmarker is None:
        with _pose_load_lock:
            if _pose_landmarker is None:
                pose_model_path = get_pose_model_path()
                logger.info("PoseLandmarker 모델을 로딩합니다. path=%s", pose_model_path)
                _pose_landmarker = vision.PoseLandmarker.create_from_options(
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

    return _pose_landmarker


def get_face_landmarker():
    global _face_landmarker

    if _face_landmarker is None:
        with _face_load_lock:
            if _face_landmarker is None:
                face_model_path = get_face_model_path()
                logger.info("FaceLandmarker 모델을 로딩합니다. path=%s", face_model_path)
                _face_landmarker = vision.FaceLandmarker.create_from_options(
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

    return _face_landmarker


@contextmanager
def whisper_model_context() -> Iterator[object]:
    model = get_whisper_model()
    with _whisper_inference_lock:
        yield model


@contextmanager
def pose_landmarker_context() -> Iterator[object]:
    landmarker = get_pose_landmarker()
    with _pose_inference_lock:
        yield landmarker


@contextmanager
def face_landmarker_context() -> Iterator[object]:
    landmarker = get_face_landmarker()
    with _face_inference_lock:
        yield landmarker


def resolve_project_root() -> Path:
    current_path = Path(__file__).resolve()

    for parent in current_path.parents:
        if parent.name == "analysis-engine":
            return parent.parent

    if (current_path / "storage").exists():
        return current_path

    if (current_path.parent / "storage").exists():
        return current_path.parent

    return current_path.parent


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
        "whisper": _whisper_model is not None,
        "pose": _pose_landmarker is not None,
        "face": _face_landmarker is not None,
    }


def is_ready() -> bool:
    return all(model_status().values())


def close_all() -> None:
    for model_name, model in (
        ("pose", _pose_landmarker),
        ("face", _face_landmarker),
    ):
        if model is not None and hasattr(model, "close"):
            try:
                model.close()
            except Exception:
                logger.warning("%s landmarker 종료 중 오류가 발생했습니다.", model_name, exc_info=True)


atexit.register(close_all)
