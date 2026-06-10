def analyze_gesture_from_pose_results(pose_results: list):
    if not pose_results:
        return {
            "gesture_movement_count": 0,
            "gesture_score": 0,
            "gesture_level": "UNKNOWN"
        }

    wrist_positions = []

    for item in pose_results:
        if item.get("pose_detected") is not True:
            continue

        landmarks = item.get("landmarks", [])

        left_wrist = next((lm for lm in landmarks if lm["id"] == 15), None)
        right_wrist = next((lm for lm in landmarks if lm["id"] == 16), None)

        if left_wrist and right_wrist:
            wrist_positions.append({
                "left_x": left_wrist["x"],
                "left_y": left_wrist["y"],
                "right_x": right_wrist["x"],
                "right_y": right_wrist["y"]
            })

    if len(wrist_positions) < 2:
        return {
            "gesture_movement_count": 0,
            "gesture_score": 40,
            "gesture_level": "LOW"
        }

    movement_count = 0

    for i in range(1, len(wrist_positions)):
        prev = wrist_positions[i - 1]
        curr = wrist_positions[i]

        left_move = abs(curr["left_x"] - prev["left_x"]) + abs(curr["left_y"] - prev["left_y"])
        right_move = abs(curr["right_x"] - prev["right_x"]) + abs(curr["right_y"] - prev["right_y"])

        if left_move > 0.08 or right_move > 0.08:
            movement_count += 1

    if 2 <= movement_count <= 8:
        gesture_score = 100
        gesture_level = "GOOD"
    elif movement_count == 0:
        gesture_score = 40
        gesture_level = "LOW"
    elif movement_count < 2:
        gesture_score = 70
        gesture_level = "LOW"
    elif movement_count <= 14:
        gesture_score = 70
        gesture_level = "HIGH"
    else:
        gesture_score = 40
        gesture_level = "TOO_HIGH"

    return {
        "gesture_movement_count": movement_count,
        "gesture_score": gesture_score,
        "gesture_level": gesture_level
    }