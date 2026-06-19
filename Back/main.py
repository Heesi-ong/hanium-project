"""Uvicorn 실행 호환성을 위해 실제 앱 객체를 Back.app.main에서 다시 노출한다."""

try:
    from .app.main import app
except ImportError:
    from app.main import app

__all__ = ["app"]
