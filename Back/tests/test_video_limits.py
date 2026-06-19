import unittest
from unittest.mock import patch

from Back.app.services import video_info


class VideoLimitTests(unittest.TestCase):
    def valid_video(self):
        return {"fps": 30, "frame_count": 300, "width": 1920, "height": 1080, "duration_seconds": 10}

    def test_valid_video_includes_expected_extracted_frames(self):
        result = video_info.validate_video_info(self.valid_video())
        self.assertEqual(result["expected_extracted_frames"], 2)

    def test_invalid_or_non_finite_metadata_is_rejected(self):
        metadata = self.valid_video()
        metadata["fps"] = float("nan")
        with self.assertRaisesRegex(ValueError, "정상 영상"):
            video_info.validate_video_info(metadata)

    @patch.object(video_info, "MAX_VIDEO_DURATION_SECONDS", 5)
    def test_overlong_video_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "영상 길이"):
            video_info.validate_video_info(self.valid_video())

    @patch.object(video_info, "MAX_VIDEO_WIDTH", 1280)
    def test_oversized_resolution_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "영상 너비"):
            video_info.validate_video_info(self.valid_video())


if __name__ == "__main__":
    unittest.main()
