# Real-Time Streaming RAG System

A local portfolio MVP demonstrating event-driven knowledge ingestion with Java 21, Spring Boot, Spring Kafka, Spring AI, ChromaDB, OpenAI and Thymeleaf.

## Features

- Publish versioned knowledge events to Kafka over HTTP.
- **OCR PDF Support**: Upload both digital text PDFs and scanned image-based PDFs (built-in automatic OCR powered by Tesseract / Tess4J with zero manual installation required).
- Multi-page PDF text extraction with structure and page-aware chunking (`[[PAGE:n]]`).
- Kafka consumer chunks content, creates vector embeddings through Spring AI and stores chunks in ChromaDB.
- Time-aware retrieval based on `validFrom` / `validTo` metadata.
- Ask questions with an optional ISO-8601 `asOf` instant for historical questions.
- Display retrieved source/version metadata and page-level citations with the generated answer.
- Modern Gemini-inspired web UI with multi-turn conversations, PDF inspection, replacement flow, and document filtering.

## Requirements

- JDK 21
- Maven 3.9+
- Docker Desktop / Docker Engine with Compose
- OpenAI API key with API access and billing as required

## Start infrastructure

```bash
docker compose up -d
docker compose ps
```

Kafka: `localhost:9092`  
ChromaDB: `localhost:8000`

## Configure OpenAI

PowerShell:
```powershell
$env:OPENAI_API_KEY="your-api-key"
```

macOS/Linux:
```bash
export OPENAI_API_KEY="your-api-key"
```

The key is intentionally not stored in source code. API usage can cost money independently of a ChatGPT subscription.

## Run

```bash
mvn clean test
mvn spring-boot:run
```

Open <http://localhost:8080>.

## Test with sample events

PowerShell:
```powershell
$body = Get-Content .\sample-data\warranty-policy-v1.json -Raw
Invoke-RestMethod -Uri http://localhost:8080/api/knowledge/events -Method Post -ContentType 'application/json' -Body $body
```

Then submit `warranty-policy-v2.json`. Ask the assistant what warranty applied on a date before 2026-10-22, then ask about a date after that time. Note that each version's validity window must be supplied accurately by the source system.

Cross-platform curl example:
```bash
curl -X POST http://localhost:8080/api/knowledge/events \
  -H 'Content-Type: application/json' \
  --data-binary @sample-data/warranty-policy-v1.json
```

## API endpoints

- `GET /` - Thymeleaf web UI
- `POST /api/knowledge/events` - publish a JSON knowledge event
- `POST /api/knowledge/pdf` - multipart PDF upload (`file` field)
- `POST /api/chat` - JSON `{ "question": "...", "asOf": "2026-10-22T00:00:00Z" }`; `asOf` is optional
- `GET /actuator/health` - health endpoint

## Event schema

```json
{
  "eventId": "unique-event-id",
  "documentId": "warranty-policy",
  "title": "Warranty Policy",
  "content": "The policy text...",
  "version": 2,
  "validFrom": "2026-10-22T00:00:00Z",
  "validTo": null,
  "operation": "UPSERT",
  "source": "warranty-team"
}
```

- `UPSERT`: indexes a new version. Use a unique `eventId` and monotonically increasing `version` per document.
- `DELETE`: writes a tombstone version so later searches can suppress that tombstone. This MVP does not physically erase prior chunks from ChromaDB.
- Use UTC ISO-8601 instants, e.g. `2026-10-22T00:00:00Z`.

## Architecture

`PDF upload / event API -> Kafka topic -> Spring Kafka consumer -> text chunking -> OpenAI embeddings -> ChromaDB -> time-aware similarity retrieval -> OpenAI chat completion -> source-aware answer`

## MVP limitations and production hardening

This is a learning/portfolio MVP, not a production guarantee of strict consistency.

- Event deduplication and latest-version tracking are held in memory and reset on application restart. For production, persist an event ledger and document-version state in a transactional database.
- Temporal validity filtering is applied after vector similarity retrieval. For large datasets, implement metadata prefilters or a dedicated temporal/version index so old chunks cannot crowd out relevant current chunks.
- A `DELETE` event creates a tombstone but does not physically delete earlier vector records. Add an audited deletion workflow if required.
- Kafka retries/dead-letter topic, schema evolution, authentication, authorization, rate limits, monitoring and backpressure need production configuration.
- PDF uploads support selectable text. Scanned PDFs need OCR.
- The MVP uses overlapping fixed-size chunks, not semantic chunking.
- A source's validity periods must be correct; temporal reasoning cannot infer policy effective dates reliably without trustworthy metadata.
- OpenAI calls require internet access, a valid API key, model availability and API quota.

## Troubleshooting

- Kafka not ready: check `docker compose logs kafka` and `docker compose ps`.
- Chroma not ready: check `docker compose logs chroma` and `http://localhost:8000/api/v2/heartbeat`.
- OpenAI error: confirm `OPENAI_API_KEY`, API access, quota and model names.
- Chat returns no evidence: wait for Kafka consumer indexing to finish and check Spring Boot logs.
- Dependency errors: run `mvn -U clean package` and verify JDK 21 with `java -version` and `mvn -version`.
