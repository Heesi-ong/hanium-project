"""ffmpeg로 영상 음성 트랙의 평균 음량과 음량 안정성 정보를 계산한다."""

import subprocess


def analyze_volume_from_video(file_path: str):
    try:
        command = [
            "ffmpeg",
            "-i", file_path,
            "-af", "volumedetect",
            "-f", "null",
            "-"
        ]

        result = subprocess.run(
            command,
            stderr=subprocess.PIPE,
            stdout=subprocess.PIPE,
            text=True
        )

        stderr = result.stderr

        mean_volume = None
        max_volume = None

        for line in stderr.splitlines():
            if "mean_volume:" in line:
                mean_volume = float(
                    line.split("mean_volume:")[1]
                    .replace("dB", "")
                    .strip()
                )

            if "max_volume:" in line:
                max_volume = float(
                    line.split("max_volume:")[1]
                    .replace("dB", "")
                    .strip()
                )

        if mean_volume is None:
            return {
                "mean_volume_db": None,
                "max_volume_db": None,
                "volume_score": 0,
                "volume_level": "UNKNOWN"
            }

        if -30 <= mean_volume <= -15:
            volume_score = 100
            volume_level = "GOOD"
        elif -40 <= mean_volume < -30:
            volume_score = 70
            volume_level = "LOW"
        elif -15 < mean_volume <= -5:
            volume_score = 70
            volume_level = "HIGH"
        else:
            volume_score = 40
            volume_level = "BAD"

        return {
            "mean_volume_db": mean_volume,
            "max_volume_db": max_volume,
            "volume_score": volume_score,
            "volume_level": volume_level
        }

    except Exception as e:
        return {
            "mean_volume_db": None,
            "max_volume_db": None,
            "volume_score": 0,
            "volume_level": "ERROR",
            "error": str(e)
        }
