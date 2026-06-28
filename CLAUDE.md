# CLAUDE.md — DevAssist AI

Project-level guidance for Claude Code. Read this before touching any file.

---

## Project Overview

DevAssist AI is a full-stack AI developer tool with three independently deployed services:

- **Frontend** (`frontend/`) — Pure HTML/JS/CSS, no framework. Deployed on Vercel. Also mirrored into `backend/.../resources/static/` for self-contained Spring Boot serving.
- **Backend** (`backend/devassist-backend/`) — Spring Boot 3, Java 21, Maven. Deployed on Render.com via Docker.
- **AI Engine** (`ai-engine/`) — FastAPI, Python 3.11. Deployed on Render.com.

Infrastructure: PostgreSQL via Supabase (PgBouncer transaction pool), Supabase Auth, Supabase Storage, Groq API (LLM), Wandbox (code execution sandbox).

---

## Rules — Always Follow These

### Safety
- **Never delete any file without explicit user approval.** Ask first, even for files that look like dead code or debug artifacts.
- **Never commit secrets.** Do not add API keys, passwords, or tokens to any file. Use environment variables only.
- **Always explain major changes before making them.** For anything that touches more than one file, or changes a public API, describe what you are about to do and why, and wait for approval.
- **Never force-push or reset --hard** without explicit user instruction.

### Communication
- Before starting a multi-file change, state the plan in one short paragraph.
- After finishing, state what changed and what the user should verify.
- If you find a bug while working on something else, report it — do not silently fix it unless asked.

### Tests
- Write tests for every new feature.
- Do not mark a task complete if its tests are failing.
- Tests live alongside the code they cover: Java tests in `src/test/`, Python tests in `ai-engine/tests/` (create if absent).

---

## Backend — Java / Spring Boot

### Java Version
Use **Java 21**. Use modern language features where they improve clarity:
- Records for DTOs and value objects
- Sealed classes for closed type hierarchies
- Pattern matching (`instanceof`, `switch`)
- Text blocks for multi-line strings (SQL, JSON templates)
- `var` for local variables where the type is obvious from context

### Architecture — Clean Architecture
The backend follows a layered Clean Architecture. Maintain strict layer boundaries:

```
Controller  →  Service  →  Repository  →  Entity/DB
     ↓              ↓
    DTO          Domain Model
```

- **Controllers** (`controller/`) — HTTP only. Validate input, call one service, return `ResponseEntity`. No business logic.
- **Services** (`service/`) — Business logic only. No direct repository calls from controllers. No HTTP concerns.
- **Repositories** (`repository/`) — Spring Data JPA interfaces only. No SQL in services.
- **Entities** (`entity/`) — JPA-mapped domain objects. No business methods, no HTTP imports.
- **DTOs** (`dto/`) — Data transfer objects for request/response. Use Java records for new DTOs.
- **Config** (`config/`) — Spring `@Configuration` beans. Security, CORS, RestTemplate, etc.
- **Security** (`security/`) — JWT filter and utility only.

### SOLID Principles
- **Single Responsibility** — one class, one reason to change. A controller that also talks to the DB is wrong.
- **Open/Closed** — extend behavior via interfaces and injection, not by editing existing classes.
- **Liskov Substitution** — subtypes must be usable in place of their supertype without surprises.
- **Interface Segregation** — prefer narrow, focused interfaces over fat ones.
- **Dependency Inversion** — depend on abstractions (interfaces), not concrete implementations. Inject via constructor, not field `@Autowired`.

Prefer **constructor injection** over `@Autowired` field injection. It makes dependencies explicit and easier to test.

```java
// Preferred
public class AiController {
    private final DocumentRepository documentRepository;
    private final AiService aiService;

    public AiController(DocumentRepository documentRepository, AiService aiService) {
        this.documentRepository = documentRepository;
        this.aiService = aiService;
    }
}
```

### Spring Boot Conventions
- Use `application.properties` for configuration. All secrets must come from environment variables with no hardcoded default that is a real credential.
- Register new beans in the appropriate `@Configuration` class, not as scattered `@Component` unless it is a service/repository/controller.
- Use `@Transactional` at the service layer, not the controller layer.
- Use `ResponseEntity<?>` for controller return types. Return proper HTTP status codes — `400` for bad input, `404` for not found, `500` for unexpected server errors.
- Keep `spring.jpa.hibernate.ddl-auto=none`. Schema changes go through explicit migration scripts, not Hibernate auto-DDL.

### Naming
- Classes: `PascalCase`
- Methods and variables: `camelCase`
- Constants: `UPPER_SNAKE_CASE`
- Packages: `lowercase`
- REST endpoints: `kebab-case` paths (e.g., `/api/global-chat`, not `/api/globalChat`)

### Testing (Java)
- Use JUnit 5 and Mockito.
- Unit-test service classes in isolation — mock all repository dependencies.
- Integration-test controllers with `@WebMvcTest` or `@SpringBootTest`.
- Test class name: `{ClassName}Test` in the same package under `src/test/`.
- Every new service method needs at least: a happy-path test, and one edge/error-path test.

---

## AI Engine — Python / FastAPI

### Style
- Follow PEP 8. Max line length 100.
- Type-hint all function signatures.
- Use `async def` for route handlers. Use `def` for pure utility functions.
- Import heavy libraries (`faiss`, `sentence_transformers`) inside the function body, not at module level, to keep startup fast on Render free tier.

### Architecture
- Route handlers in `api/api_server.py` — HTTP only. Extract business logic into `services/` or `rag/`.
- LLM calls go through `core/llm.py:safe_generate` only. Do not call the Groq client directly from route handlers.
- Thread-safety: all shared in-memory state (`rag_cache`, `doc_chat_history`, `global_chat_history`) must be accessed under the corresponding `threading.Lock`.

### Dependencies
- Add every new package to `ai-engine/requirements.txt` immediately. Do not import a package that is not listed there.
- Do not add packages that require compiled binaries (PyTorch, FAISS) unless you have confirmed they are available on Render's free tier build environment and the build time is acceptable.

### Testing (Python)
- Use `pytest`.
- Place tests in `ai-engine/tests/`.
- Mock the Groq client in unit tests — do not make real API calls in tests.

---

## Frontend — HTML / JS

- No framework. Vanilla JS only.
- Auth is always handled via `auth-guard.js`. Every protected page must call `requireAuth()` at `DOMContentLoaded`.
- All API calls to the backend must use `authFetch()` from `auth-guard.js` — it attaches the Supabase JWT automatically.
- Never hardcode the backend URL inline. Use `BACKEND_URL` from `auth-guard.js` or `API_CONFIG.BACKEND_URL` from `api-config.js`.
- When you add or change a frontend page, also update its mirror copy in `backend/devassist-backend/src/main/resources/static/` to keep both in sync.

---

## Environment Variables

Never use a real credential as a default value. The pattern must be:

```properties
# Correct
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}

# Wrong — real password as fallback
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:Sudharsan@2005}
```

Required environment variables are documented in:
- `render.yaml` — for production Render deployment
- `LOCAL_SETUP_GUIDE.md` — for local development

---

## Deployment

- **Backend** — Dockerized. `backend/devassist-backend/Dockerfile`. Deployed to Render.com.
- **AI Engine** — Python runtime on Render.com. Start command: `uvicorn api.api_server:app --host 0.0.0.0 --port 10000`.
- **Frontend** — Static files on Vercel. Config in `frontend/vercel.json`.
- The `KeepAliveService` pings the AI engine every 14 minutes to prevent Render free-tier cold starts. Do not remove or disable it.

---

## Known Technical Debt (do not silently remove — discuss first)

- `AiIntegrationService.java` — hardcodes `127.0.0.1:8000` and references `/secret-intelligence` (endpoint does not exist). Dead code, but leave it until a cleanup task is approved.
- `question_answering.py` — contains FAISS-based vector search logic that is bypassed in production. The `/rag-ask` route sends raw text to the LLM instead.
- `User.password` field — unused column left from before Supabase auth migration.
- `normalize()` in `VoidMainController` — collapses newlines in expected output, which may cause false Wrong Answer verdicts on multi-line problems.
