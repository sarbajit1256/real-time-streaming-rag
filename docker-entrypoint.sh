#!/bin/sh
set -e

# If CHROMA_HOST is default/localhost, start internal ChromaDB server
CHROMA_TARGET="${CHROMA_HOST:-http://localhost}"
if [ "$CHROMA_TARGET" = "http://localhost" ] || [ "$CHROMA_TARGET" = "localhost" ] || [ "$CHROMA_TARGET" = "http://127.0.0.1" ]; then
    echo "[Entrypoint] Starting built-in ChromaDB on port 8000..."
    mkdir -p /data/chroma
    python3 -m chromadb.cli.cli run --path /data/chroma --port 8000 &
    CHROMA_PID=$!
    echo "[Entrypoint] ChromaDB started with PID $CHROMA_PID"
    sleep 3
fi

echo "[Entrypoint] Starting Real-Time Streaming RAG Application on port ${PORT:-7860}..."
exec java $JAVA_OPTS -jar app.jar
