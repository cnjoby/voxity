FROM ghcr.io/astral-sh/uv:0.11.11 AS uv
FROM python:3.12-slim
COPY --from=uv /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy SONAR_DATA_DIR=/data
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev
COPY app ./app
COPY .env.example ./
RUN useradd --uid 10001 --create-home sonar && mkdir /data && chown sonar:sonar /data
USER sonar
EXPOSE 8000
CMD [".venv/bin/uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", "--no-access-log"]
