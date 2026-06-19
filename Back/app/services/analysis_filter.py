"""기존 분석 JSON을 발표 평가 기준에 맞게 1차 필터링한다."""


def _number(value):
    if isinstance(value, bool):
        return None
    return value if isinstance(value, (int, float)) else None


def _nested(data, *keys):
    current = data
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def _metric(data, key):
    for value in (
        _nested(data, "score_result", key),
        _nested(data, "audio_result", key),
        _nested(data, "summary_result", key),
        data.get(key),
    ):
        if value is not None:
            return value
    return None


def _overall_level(total_score):
    score = _number(total_score)
    if score is None:
        return "측정 불가"
    if score >= 80:
        return "우수"
    if score >= 60:
        return "보통"
    return "개선 필요"


def _issue(category, severity, message, evidence):
    return {
        "category": category,
        "severity": severity,
        "message": message,
        "evidence": evidence,
    }


def _strength(category, message, evidence=None):
    result = {"category": category, "message": message}
    if evidence:
        result["evidence"] = evidence
    return result


def filter_analysis_result(raw_analysis_result: dict) -> dict:
    """분석 결과에서 우선 개선 항목과 강점을 추출한다.

    점수 계산을 다시 하지 않고 기존 분석 결과의 측정값만 읽는다.
    """

    data = raw_analysis_result or {}
    total_score = _metric(data, "total_score")
    speech_speed_wpm = _metric(data, "speech_speed_wpm")
    speech_speed_spm = _metric(data, "speech_speed_spm")
    speech_speed_basis = _metric(data, "speech_speed_basis")
    silence_count = _metric(data, "silence_count")
    total_silence_time = _metric(data, "total_silence_time")
    filler_count = _metric(data, "filler_count")
    pose_detection_rate = _metric(data, "pose_detection_rate")
    face_detection_rate = _metric(data, "face_detection_rate")
    gaze_score = _metric(data, "gaze_score")
    shoulder_balance_score = _metric(data, "shoulder_balance_score")
    gesture_score = _metric(data, "gesture_score")
    volume_score = _metric(data, "volume_score")

    priority_issues = []
    strengths = []
    improvement_targets = []

    if _number(total_score) is not None:
        if total_score < 60:
            priority_issues.append(
                _issue(
                    "overall",
                    "high",
                    "종합 점수가 낮아 발표 전반의 개선 우선순위를 다시 잡아야 합니다.",
                    {"total_score": total_score},
                )
            )
            improvement_targets.append("종합 발표 구성 개선")
        elif total_score >= 80:
            strengths.append(_strength("overall", "종합 점수가 우수한 편입니다.", {"total_score": total_score}))

    if _number(speech_speed_spm) is not None and speech_speed_spm > 0:
        if speech_speed_spm < 250 or speech_speed_spm > 400:
            priority_issues.append(
                _issue(
                    "speech_speed",
                    "medium",
                    "한국어 발화 속도가 권장 범위를 벗어났습니다.",
                    {"speech_speed_spm": speech_speed_spm, "speech_speed_basis": speech_speed_basis},
                )
            )
            improvement_targets.append("발화 속도 조절")
        else:
            strengths.append(
                _strength(
                    "speech_speed",
                    "한국어 발화 속도가 권장 범위에 가깝습니다.",
                    {"speech_speed_spm": speech_speed_spm},
                )
            )
    elif _number(speech_speed_wpm) is not None and speech_speed_wpm > 0:
        if speech_speed_wpm < 100 or speech_speed_wpm > 160:
            priority_issues.append(
                _issue(
                    "speech_speed",
                    "medium",
                    "발화 속도가 권장 범위를 벗어났습니다.",
                    {"speech_speed_wpm": speech_speed_wpm},
                )
            )
            improvement_targets.append("발화 속도 조절")
        else:
            strengths.append(
                _strength("speech_speed", "발화 속도가 권장 범위에 가깝습니다.", {"speech_speed_wpm": speech_speed_wpm})
            )

    if _number(silence_count) is not None and _number(total_silence_time) is not None:
        if silence_count > 5 or total_silence_time > 12:
            priority_issues.append(
                _issue(
                    "silence",
                    "medium",
                    "침묵 구간이 많거나 길어 발표 흐름이 끊길 수 있습니다.",
                    {"silence_count": silence_count, "total_silence_time": total_silence_time},
                )
            )
            improvement_targets.append("침묵 구간 감소")
        elif silence_count <= 2 and total_silence_time <= 5:
            strengths.append(
                _strength(
                    "silence",
                    "침묵 구간이 적어 발표 흐름이 비교적 안정적입니다.",
                    {"silence_count": silence_count, "total_silence_time": total_silence_time},
                )
            )

    if _number(filler_count) is not None:
        if filler_count > 8:
            priority_issues.append(
                _issue(
                    "filler",
                    "medium",
                    "필러워드가 많아 발표가 덜 정돈되어 보일 수 있습니다.",
                    {"filler_count": filler_count},
                )
            )
            improvement_targets.append("필러워드 감소")
        elif filler_count <= 2:
            strengths.append(_strength("filler", "필러워드 사용이 적은 편입니다.", {"filler_count": filler_count}))

    if _number(pose_detection_rate) is not None:
        if pose_detection_rate < 70:
            priority_issues.append(
                _issue(
                    "pose_detection",
                    "high",
                    "자세 감지율이 낮아 카메라 위치나 촬영 환경 확인이 필요합니다.",
                    {"pose_detection_rate": pose_detection_rate},
                )
            )
            improvement_targets.append("촬영 환경 및 자세 인식 안정화")
        elif pose_detection_rate >= 90:
            strengths.append(
                _strength("pose_detection", "자세 인식이 안정적으로 수행되었습니다.", {"pose_detection_rate": pose_detection_rate})
            )

    if _number(face_detection_rate) is not None:
        if face_detection_rate < 70:
            priority_issues.append(
                _issue(
                    "face_detection",
                    "high",
                    "얼굴 감지율이 낮아 얼굴 방향이나 카메라 위치 개선이 필요합니다.",
                    {"face_detection_rate": face_detection_rate},
                )
            )
            improvement_targets.append("얼굴 방향 및 카메라 위치 개선")
        elif face_detection_rate >= 90:
            strengths.append(
                _strength("face_detection", "얼굴 방향 분석에 필요한 얼굴 감지가 안정적입니다.", {"face_detection_rate": face_detection_rate})
            )

    if _number(gaze_score) is not None:
        if gaze_score < 70:
            priority_issues.append(
                _issue(
                    "gaze",
                    "medium",
                    "시선 처리 점수가 낮아 청중을 바라보는 느낌이 약할 수 있습니다.",
                    {"gaze_score": gaze_score},
                )
            )
            improvement_targets.append("시선 처리 개선")
        elif gaze_score >= 85:
            strengths.append(_strength("gaze", "시선 처리가 비교적 안정적입니다.", {"gaze_score": gaze_score}))

    if _number(shoulder_balance_score) is not None:
        if shoulder_balance_score < 70:
            priority_issues.append(
                _issue(
                    "posture",
                    "medium",
                    "어깨 균형 점수가 낮아 자세가 기울어져 보일 수 있습니다.",
                    {"shoulder_balance_score": shoulder_balance_score},
                )
            )
            improvement_targets.append("자세 균형 개선")
        elif shoulder_balance_score >= 85:
            strengths.append(
                _strength(
                    "posture",
                    "자세 균형은 비교적 안정적입니다.",
                    {"shoulder_balance_score": shoulder_balance_score},
                )
            )

    if _number(gesture_score) is not None and gesture_score >= 85:
        strengths.append(_strength("gesture", "손동작 사용이 비교적 안정적입니다.", {"gesture_score": gesture_score}))

    if _number(volume_score) is not None and volume_score >= 85:
        strengths.append(_strength("voice_volume", "음량이 비교적 안정적입니다.", {"volume_score": volume_score}))

    deduped_targets = []
    for target in improvement_targets:
        if target not in deduped_targets:
            deduped_targets.append(target)

    return {
        "overall_level": _overall_level(total_score),
        "priority_issues": priority_issues,
        "strengths": strengths,
        "improvement_targets": deduped_targets,
    }
