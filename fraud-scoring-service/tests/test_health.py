"""pytest finds files named test_*.py and runs every function named test_*.

TestClient calls the app in-process (no real server or network), so tests are fast.
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_reports_up() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}
