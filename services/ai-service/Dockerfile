# syntax=docker/dockerfile:1
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app

# The embedding model and the reranker are downloaded at build time, in their own layer, so
# they're cached across code changes and the running container never needs the internet.
ARG EMBEDDING_MODEL=BAAI/bge-small-en-v1.5
# Empty = no reranker (the measured default). To turn it on, build with
#   --build-arg RERANKER_MODEL=Xenova/ms-marco-MiniLM-L-6-v2
ARG RERANKER_MODEL=
ENV EMBEDDING_MODEL=${EMBEDDING_MODEL} \
    RERANKER_MODEL=${RERANKER_MODEL} \
    EMBEDDING_CACHE_DIR=/opt/models
COPY docker/download_models.py /tmp/download_models.py
RUN pip install "fastembed>=0.9,<1" \
 && python /tmp/download_models.py \
 && chmod -R a+rX /opt/models   # readable (not writable) by the non-root app user

COPY pyproject.toml ./
COPY app/ app/
RUN pip install .

# Never reach out to Hugging Face at runtime: use only the baked-in model files.
ENV HF_HUB_OFFLINE=1
USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
