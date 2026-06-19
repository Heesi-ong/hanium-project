"""음성 인식 텍스트에서 필러 단어 사용량과 필러 단어 점수를 계산한다."""

import re


def analyze_filler_words(text: str, duration_seconds: float = 0):
    if not text:
        return {
            "filler_count": 0,
            "filler_words": {},
            "filler_score": None,
            "filler_per_minute": None,
        }

    filler_list = [
        "그러니까",
        "약간",
        "뭔가",
        "사실",
        "일단",
        "이제",
        "음",
        "어",
        "그",
        "저"
    ]

    filler_words = {}
    total_count = 0

    for word in filler_list:
        pattern = rf"(?<![가-힣a-zA-Z0-9]){re.escape(word)}(?![가-힣a-zA-Z0-9])"
        count = len(re.findall(pattern, text))

        if count > 0:
            filler_words[word] = count
            total_count += count

    filler_per_minute = round(total_count / duration_seconds * 60, 2) if duration_seconds > 0 else None
    score_count = filler_per_minute if filler_per_minute is not None else total_count

    if score_count <= 2:
        filler_score = 100
    elif score_count <= 4:
        filler_score = 85
    elif score_count <= 7:
        filler_score = 70
    elif score_count <= 10:
        filler_score = 55
    else:
        filler_score = 40

    return {
        "filler_count": total_count,
        "filler_words": filler_words,
        "filler_score": filler_score,
        "filler_per_minute": filler_per_minute,
    }
