"""분석 결과 삭제 시 연결된 대화와 메시지 기록을 함께 정리한다."""

from .database import transaction


def delete_result_conversations(result_id, user_id=None):
    with transaction() as connection:
        with connection.cursor() as cursor:
            if user_id is None:
                cursor.execute(
                    "DELETE FROM conversations WHERE analysis_result_id = %s",
                    (result_id,),
                )
            else:
                cursor.execute(
                    "DELETE FROM conversations WHERE analysis_result_id = %s AND user_id = %s",
                    (result_id, user_id),
                )
            return cursor.rowcount


def delete_result_records(result_id, user_id=None):
    with transaction() as connection:
        with connection.cursor() as cursor:
            if user_id is None:
                cursor.execute("DELETE FROM conversations WHERE analysis_result_id = %s", (result_id,))
            else:
                cursor.execute(
                    "DELETE FROM conversations WHERE analysis_result_id = %s AND user_id = %s",
                    (result_id, user_id),
                )
            deleted_conversations = cursor.rowcount
            if user_id is None:
                cursor.execute("DELETE FROM analysis_jobs WHERE id = %s AND status = 'COMPLETED'", (result_id,))
            else:
                cursor.execute(
                    "DELETE FROM analysis_jobs WHERE id = %s AND user_id = %s",
                    (result_id, user_id),
                )
            if cursor.rowcount != 1:
                raise ValueError("분석 작업 DB 레코드를 삭제하지 못했습니다.")
            return deleted_conversations
