import cv2

def get_video_info(video_path: str):
    cap = cv2.VideoCapture(video_path)

    if not cap.isOpened():
        return {
            "error": "video open failed"
        }

    fps = cap.get(cv2.CAP_PROP_FPS)
    frame_count = cap.get(cv2.CAP_PROP_FRAME_COUNT)
    width = cap.get(cv2.CAP_PROP_FRAME_WIDTH)
    height = cap.get(cv2.CAP_PROP_FRAME_HEIGHT)

    duration = frame_count / fps if fps > 0 else 0

    cap.release()

    return {
        "fps": fps,
        "frame_count": frame_count,
        "width": width,
        "height": height,
        "duration_seconds": duration
    }