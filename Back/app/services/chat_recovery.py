from ..config import CHAT_PENDING_TIMEOUT_MINUTES
from ..services.database import transaction


def recover_stale_pending_messages():
    with transaction() as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE messages
                SET metadata = JSON_SET(
                  metadata,
                  '$.chatStatus', 'failed',
                  '$.errorCode', 'InterruptedRequest'
                )
                WHERE JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.chatStatus')) = 'pending'
                  AND created_at <= DATE_SUB(NOW(3), INTERVAL %s MINUTE)
                """,
                (CHAT_PENDING_TIMEOUT_MINUTES,),
            )
            return cursor.rowcount
