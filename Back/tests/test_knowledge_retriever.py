import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from Back.app.services import knowledge_retriever


def write_document(root, filename, document_id, category, target_metric, related_service, title, body):
    (root / filename).write_text(
        f"""---
id: {document_id}
category: {category}
target_metric: {target_metric}
related_services:
  - {related_service}
priority: high
version: 1.0
---

# {title}

{body}
""",
        encoding="utf-8",
    )


class KnowledgeRetrieverTests(unittest.TestCase):
    def setUp(self):
        knowledge_retriever.clear_knowledge_cache()

    def tearDown(self):
        knowledge_retriever.clear_knowledge_cache()

    def test_parses_front_matter_and_markdown(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_document(
                root,
                "speech.md",
                "speech_speed_wpm",
                "audio",
                "wpm",
                "practice_coaching",
                "발표 속도 기준",
                "발표 속도를 조절합니다.",
            )
            with patch.object(knowledge_retriever, "KNOWLEDGE_DIR", root):
                documents = knowledge_retriever.load_knowledge_documents()

        self.assertEqual(documents[0]["id"], "speech_speed_wpm")
        self.assertEqual(documents[0]["related_services"], ["practice_coaching"])
        self.assertIn("발표 속도를 조절합니다.", documents[0]["content"])

    def test_metric_mapping_prioritizes_relevant_document(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_document(
                root,
                "speech.md",
                "speech_speed_wpm",
                "audio",
                "wpm",
                "practice_coaching",
                "발표 속도 기준",
                "문장 끝에서 잠시 멈춥니다.",
            )
            write_document(
                root,
                "generic.md",
                "generic",
                "coaching",
                "general",
                "practice_coaching",
                "일반 발표 기준",
                "일반적인 발표 연습입니다.",
            )
            with patch.object(knowledge_retriever, "KNOWLEDGE_DIR", root):
                selected = knowledge_retriever.retrieve_knowledge(
                    "발표 개선",
                    purpose="project",
                    metric_keys={"speech_speed_score"},
                    service="practice_coaching",
                    limit=2,
                )

        self.assertEqual(selected[0]["id"], "speech_speed_wpm")

    def test_chat_retrieval_prioritizes_qa_and_safety(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_document(
                root,
                "qa.md",
                "user_question_answering",
                "qa",
                "result_explanation",
                "chat",
                "사용자 질문 답변",
                "점수의 원인을 설명합니다.",
            )
            write_document(
                root,
                "audio.md",
                "voice_volume",
                "audio",
                "volume_stability",
                "feedback_generator",
                "음량",
                "음량을 설명합니다.",
            )
            with patch.object(knowledge_retriever, "KNOWLEDGE_DIR", root):
                selected = knowledge_retriever.retrieve_knowledge(
                    "왜 이 점수가 나왔나요?",
                    service="chat",
                    limit=1,
                )

        self.assertEqual(selected[0]["id"], "user_question_answering")

    def test_document_cache_reuses_unchanged_documents_and_refreshes_modified_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_document(
                root,
                "speech.md",
                "speech_speed_wpm",
                "audio",
                "wpm",
                "practice_coaching",
                "발표 속도 기준",
                "처음 내용",
            )
            original_parser = knowledge_retriever._parse_document
            with (
                patch.object(knowledge_retriever, "KNOWLEDGE_DIR", root),
                patch.object(knowledge_retriever, "_parse_document", wraps=original_parser) as parser,
            ):
                knowledge_retriever.load_knowledge_documents()
                knowledge_retriever.load_knowledge_documents()
                self.assertEqual(parser.call_count, 1)

                with (root / "speech.md").open("a", encoding="utf-8") as file:
                    file.write("\n변경된 내용")
                documents = knowledge_retriever.load_knowledge_documents()

        self.assertEqual(parser.call_count, 2)
        self.assertIn("변경된 내용", documents[0]["content"])

    def test_malformed_document_does_not_block_other_knowledge(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "broken.md").write_text("---\nid: broken", encoding="utf-8")
            write_document(
                root,
                "valid.md",
                "valid",
                "coaching",
                "general",
                "chat",
                "정상 문서",
                "정상 내용",
            )
            with patch.object(knowledge_retriever, "KNOWLEDGE_DIR", root):
                documents = knowledge_retriever.load_knowledge_documents()

        self.assertEqual([document["id"] for document in documents], ["valid"])


if __name__ == "__main__":
    unittest.main()
