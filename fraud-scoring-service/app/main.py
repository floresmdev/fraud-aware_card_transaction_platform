"""Fraud Scoring Service: Phase 0 skeleton.

Only the health endpoint exists for now. Phase 2 adds POST /score (model + rules).

FastAPI builds the routing, request validation (via Pydantic type hints) and the OpenAPI docs
(/docs) from ordinary Python functions, the same idea as Spring's annotated controllers.
"""

from fastapi import FastAPI

app = FastAPI(title="Fraud Scoring Service", version="0.1.0")


@app.get("/health")
def health() -> dict[str, str]:
    """Liveness probe ("is the process up?"), used by docker-compose and ECS health checks."""
    return {"status": "UP"}
