"""얼굴 방향 검증용으로 분석 결과의 예측값과 라벨 비교 데이터를 구성한다."""

def build_face_direction_comparison(result_file):
    result_id = result_file.get("result_id")
    data = result_file.get("data", {})
    algorithm_version = data.get("analysis_metadata", {}).get("algorithm_version")
    timeline = data.get("timeline_result", {}).get("timeline", [])
    face_results = data.get("face_results", [])

    rows = []
    for index, item in enumerate(timeline):
        face_result = face_results[index] if index < len(face_results) else {}
        rows.append(
            {
                "result_id": result_id,
                "algorithm_version": algorithm_version,
                "time_sec": item.get("time_sec"),
                "face_detected": face_result.get("face_detected") is True,
                "gaze_score": item.get("gaze_score"),
                "head_direction_score": item.get("head_direction_score"),
                "yaw_degrees": item.get("yaw_degrees"),
                "pitch_degrees": item.get("pitch_degrees"),
                "roll_degrees": item.get("roll_degrees"),
            }
        )

    comparable_rows = [
        row
        for row in rows
        if _is_number(row["gaze_score"]) and _is_number(row["head_direction_score"])
    ]
    score_differences = [
        abs(row["gaze_score"] - row["head_direction_score"])
        for row in comparable_rows
    ]
    agreement_count = sum(
        row["gaze_score"] == row["head_direction_score"]
        for row in comparable_rows
    )

    return {
        "result_id": result_id,
        "algorithm_version": algorithm_version,
        "summary": {
            "timeline_frame_count": len(rows),
            "face_detected_frame_count": sum(row["face_detected"] for row in rows),
            "legacy_gaze_available_frame_count": sum(_is_number(row["gaze_score"]) for row in rows),
            "head_direction_available_frame_count": sum(
                _is_number(row["head_direction_score"]) for row in rows
            ),
            "comparable_frame_count": len(comparable_rows),
            "exact_score_agreement_count": agreement_count,
            "exact_score_agreement_rate": (
                round(agreement_count / len(comparable_rows) * 100, 2)
                if comparable_rows
                else None
            ),
            "mean_absolute_score_difference": (
                round(sum(score_differences) / len(score_differences), 2)
                if score_differences
                else None
            ),
        },
        "rows": rows,
    }


def _is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)
