import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from Back.app.services import analysis_pipeline


class AnalysisPipelineFramePersistenceTest(unittest.TestCase):
    def test_completed_analysis_keeps_five_second_frames_in_result_json(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            upload_dir = root / "uploads"
            frame_dir = root / "frames"
            result_dir = root / "results"
            for path in (upload_dir, frame_dir, result_dir):
                path.mkdir()
            source_file = upload_dir / "job-1.mp4"
            source_file.write_bytes(b"video")

            saved_payload = {}

            def save_result(data, result_id=None):
                saved_payload["data"] = data
                (result_dir / f"{result_id}.json").write_text("{}", encoding="utf-8")
                return {"result_id": result_id}

            frame_result = {
                "saved_count": 3,
                "saved_bytes": 300,
                "interval_seconds": 5,
                "output_dir": str(frame_dir / "job-1"),
                "frames": [
                    str(frame_dir / "job-1" / "frame_0.jpg"),
                    str(frame_dir / "job-1" / "frame_1.jpg"),
                    str(frame_dir / "job-1" / "frame_2.jpg"),
                ],
            }

            with ExitStack() as stack:
                stack.enter_context(patch.object(analysis_pipeline, "UPLOAD_DIR", upload_dir))
                stack.enter_context(patch.object(analysis_pipeline, "FRAME_DIR", frame_dir))
                stack.enter_context(patch.object(analysis_pipeline, "RESULT_DIR", result_dir))
                stack.enter_context(patch.object(analysis_pipeline, "ANALYSIS_FRAME_INTERVAL_SECONDS", 5))
                stack.enter_context(patch.object(analysis_pipeline, "is_cancel_requested", return_value=False))
                stack.enter_context(patch.object(analysis_pipeline, "update_job_progress", return_value=True))
                stack.enter_context(
                    patch.object(analysis_pipeline, "get_video_info", return_value={"duration_seconds": 15})
                )
                extract_frames = stack.enter_context(
                    patch.object(analysis_pipeline, "extract_frames", return_value=frame_result)
                )
                stack.enter_context(
                    patch.object(
                        analysis_pipeline,
                        "_analyze_frames",
                        side_effect=[[{"pose_detected": True}], [{"face_detected": True}]],
                    )
                )
                stack.enter_context(
                    patch.object(
                        analysis_pipeline,
                        "analyze_timeline_scores",
                        return_value={"timeline_count": 3, "timeline": []},
                    )
                )
                stack.enter_context(
                    patch.object(
                        analysis_pipeline,
                        "analyze_audio_from_video",
                        return_value={"duration_seconds": 15, "text": ""},
                    )
                )
                stack.enter_context(patch.object(analysis_pipeline, "analyze_filler_words", return_value={"filler_count": 0}))
                stack.enter_context(patch.object(analysis_pipeline, "analyze_gesture_from_pose_results", return_value={}))
                stack.enter_context(patch.object(analysis_pipeline, "analyze_volume_from_video", return_value={}))
                stack.enter_context(patch.object(analysis_pipeline, "calculate_basic_score", return_value={"total_score": 80}))
                stack.enter_context(patch.object(analysis_pipeline, "generate_feedback", return_value={"summary": "좋습니다."}))
                stack.enter_context(patch.object(analysis_pipeline, "filter_analysis_result", return_value={"overall_level": "우수"}))
                stack.enter_context(patch.object(analysis_pipeline, "generate_llm_feedback", return_value={"summary": "최종 요약"}))
                stack.enter_context(patch.object(analysis_pipeline, "save_analysis_result", side_effect=save_result))
                stack.enter_context(patch.object(analysis_pipeline, "mark_job_completed", return_value=True))
                stack.enter_context(patch.object(analysis_pipeline, "mark_job_failed"))
                remove_directory = stack.enter_context(patch.object(analysis_pipeline, "safe_remove_directory"))
                stack.enter_context(patch.object(analysis_pipeline, "ensure_file_removed", return_value=True))
                stack.enter_context(patch.object(analysis_pipeline, "clear_source_file"))
                analysis_pipeline.run_analysis_job(
                    {
                        "id": "job-1",
                        "original_filename": "presentation.mp4",
                        "saved_filename": "job-1.mp4",
                    }
                )

            extract_frames.assert_called_once_with(str(source_file), interval_sec=5, output_id="job-1")
            remove_directory.assert_not_called()
            self.assertEqual(saved_payload["data"]["frame_result"]["interval_seconds"], 5)
            self.assertEqual(saved_payload["data"]["frame_result"]["frames"], frame_result["frames"])
            self.assertEqual(
                saved_payload["data"]["raw_analysis_result"]["frame_result"]["frames"],
                frame_result["frames"],
            )


if __name__ == "__main__":
    unittest.main()
