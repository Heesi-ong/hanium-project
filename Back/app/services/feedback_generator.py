"""분석 점수와 측정값을 사용자가 읽을 수 있는 장점·개선점 피드백으로 변환한다."""

def generate_feedback(score_result: dict):
    feedback = []

    total_score = score_result.get("total_score", 0)
    pose_rate = score_result.get("pose_detection_rate", 0)
    face_rate = score_result.get("face_detection_rate", 0)
    shoulder_score = score_result.get("shoulder_balance_score", 0)
    gaze_score = score_result.get("gaze_score", 0)
    speech_speed = score_result.get("speech_speed_wpm", 0)
    speech_speed_spm = score_result.get("speech_speed_spm")
    speech_speed_score = score_result.get("speech_speed_score", 0)
    silence_count = score_result.get("silence_count", 0)
    total_silence_time = score_result.get("total_silence_time", 0)
    silence_score = score_result.get("silence_score", 0)
    filler_count = score_result.get("filler_count", 0)
    filler_words = score_result.get("filler_words", {})
    filler_score = score_result.get("filler_score", 100)
    gesture_count = score_result.get("gesture_movement_count", 0)
    gesture_score = score_result.get("gesture_score", 0)
    gesture_level = score_result.get("gesture_level", "UNKNOWN")
    mean_volume_db = score_result.get("mean_volume_db")
    volume_score = score_result.get("volume_score", 0)
    volume_level = score_result.get("volume_level", "UNKNOWN")
    visual_confidence = score_result.get("analysis_confidence", {}).get("visual", {})

    if total_score is None:
        feedback.append("측정 가능한 데이터가 부족해 종합 점수를 계산하지 않았습니다.")
    elif total_score >= 80:
        feedback.append("전체 발표 자세, 얼굴 방향, 말하기 흐름이 안정적입니다.")
    elif total_score >= 60:
        feedback.append("전체적으로 무난하지만 자세, 얼굴 방향, 말하기 흐름 중 일부 개선이 필요합니다.")
    else:
        feedback.append("발표 자세, 얼굴 방향, 말하기 흐름 전반의 개선이 필요합니다.")

    if pose_rate is None:
        feedback.append("발표자 자세가 감지되지 않아 자세 항목을 종합 점수에서 제외했습니다.")
    elif not visual_confidence.get("pose_evaluation_available", shoulder_score is not None):
        feedback.append("자세 감지 데이터가 기준보다 적어 자세 평가는 측정 불가로 처리했습니다.")
    elif pose_rate < 70:
        feedback.append("자세 감지율이 낮아 분석 신뢰도가 제한적입니다. 감지율은 발표 실력 점수에 포함하지 않습니다.")

    if face_rate is None:
        feedback.append("얼굴이 감지되지 않아 얼굴 방향 항목을 종합 점수에서 제외했습니다.")
    elif not visual_confidence.get("face_evaluation_available", gaze_score is not None):
        feedback.append("얼굴 감지 데이터가 기준보다 적어 얼굴 방향 평가는 측정 불가로 처리했습니다.")
    elif face_rate < 70:
        feedback.append("얼굴 감지율이 낮아 얼굴 방향 분석 신뢰도가 제한적입니다. 감지율은 발표 실력 점수에 포함하지 않습니다.")

    if shoulder_score is not None and shoulder_score < 70:
        feedback.append("어깨 균형이 일정하지 않아 자세가 흔들려 보일 수 있습니다.")

    if gaze_score is not None and gaze_score < 70:
        feedback.append("얼굴 정면 방향이 안정적으로 유지되지 않은 구간이 있습니다.")

    if speech_speed_score is None:
        feedback.append("음성이 인식되지 않아 말하기 속도, 침묵, 필러 사용을 평가에서 제외했습니다.")
    elif speech_speed_score < 70:
        if isinstance(speech_speed_spm, (int, float)) and speech_speed_spm > 0:
            if speech_speed_spm < 250:
                feedback.append("한국어 음절 기준 말하기 속도가 다소 느려 발표 흐름이 늘어질 수 있습니다.")
            elif speech_speed_spm > 400:
                feedback.append("한국어 음절 기준 말하기 속도가 빨라 청중이 내용을 따라가기 어려울 수 있습니다.")
            else:
                feedback.append("한국어 음절 기준 말하기 속도에 개선이 필요합니다.")
        elif speech_speed == 0:
            feedback.append("음성이 인식되지 않아 말하기 속도를 평가할 수 없습니다.")
        elif speech_speed < 100:
            feedback.append("말하기 속도가 다소 느려 발표 흐름이 늘어질 수 있습니다.")
        elif speech_speed > 190:
            feedback.append("말하기 속도가 빨라 청중이 내용을 따라가기 어려울 수 있습니다.")
        else:
            feedback.append("말하기 속도에 개선이 필요합니다.")

    if silence_score is not None and silence_score < 70:
        feedback.append(
            f"침묵 구간이 {silence_count}회, 총 {total_silence_time}초 감지되어 발표 흐름이 끊길 수 있습니다."
        )

    if filler_score is not None and filler_score < 70:
        if filler_words:
            most_used_filler = max(filler_words, key=filler_words.get)
            feedback.append(
                f"반복어 또는 필러 단어가 총 {filler_count}회 감지되었습니다. 특히 '{most_used_filler}' 표현이 자주 사용되어 발표가 덜 정돈되어 보일 수 있습니다."
            )
        else:
            feedback.append(
                f"반복어 또는 필러 단어가 총 {filler_count}회 감지되어 발표 흐름이 어색해질 수 있습니다."
            )

    if gesture_score is not None and gesture_score < 70:
        if gesture_level == "LOW":
            feedback.append(
                f"손동작 변화가 {gesture_count}회로 적게 감지되어 발표가 다소 정적으로 보일 수 있습니다."
            )
        elif gesture_level == "TOO_HIGH":
            feedback.append(
                f"손동작 변화가 {gesture_count}회로 많게 감지되어 발표 자세가 산만해 보일 수 있습니다."
            )
        else:
            feedback.append("손동작 사용에 개선이 필요합니다.")

    if volume_score is not None and volume_score < 70:
        if volume_level == "LOW":
            feedback.append(
                f"평균 음량이 {mean_volume_db}dB로 낮게 감지되어 발표 전달력이 약해질 수 있습니다."
            )
        elif volume_level == "HIGH":
            feedback.append(
                f"평균 음량이 {mean_volume_db}dB로 높게 감지되어 청중에게 부담스럽게 들릴 수 있습니다."
            )
        elif volume_level == "BAD":
            feedback.append(
                f"평균 음량이 {mean_volume_db}dB로 적정 범위를 벗어나 음성 전달 품질이 낮을 수 있습니다."
            )
        else:
            feedback.append("음량 분석이 안정적으로 수행되지 않아 발표 음성 크기 평가가 제한됩니다.")

    return {
        "summary": feedback[0],
        "details": feedback
    }
