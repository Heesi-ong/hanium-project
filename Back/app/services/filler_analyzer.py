import re


def analyze_filler_words(text: str):
    if not text:
        return {
            "filler_count": 0,
            "filler_words": {},
            "filler_score": None
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

    if total_count <= 3:
        filler_score = 100
    elif total_count <= 6:
        filler_score = 85
    elif total_count <= 10:
        filler_score = 70
    elif total_count <= 15:
        filler_score = 55
    else:
        filler_score = 40

    return {
        "filler_count": total_count,
        "filler_words": filler_words,
        "filler_score": filler_score
    }
