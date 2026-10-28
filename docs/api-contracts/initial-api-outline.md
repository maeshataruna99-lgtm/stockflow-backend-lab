# Initial API contract outline

Status: PROPOSED (Phase 0). Real contracts harden per phase; once services
exist, OpenAPI must reflect actual behavior (Phase 3 acceptance criterion).

## Common conventions

- All business paths under `/api/v1/...`.
- JSON bodies only. JPA entities are never serialized; controllers exchange
  request/response DTOs validated with Bean Validation.
- Pagination: `page` (0-based) and `size`; default 20, maximum 100, with each
  list endpoint documenting its cap.
- Errors: RFC 7807 `application/problem+json` via `@RestControllerAdvice`,
  consistent across services; the gateway later maps its own failures into the
  same contract (Phase 5).
- Each service serves OpenAPI via springdoc (`/v3/api-docs`, Swagger UI);
  pinned version in [ADR 0001](../decisions/0001-version-baseline.md).

## catalog-service (planned Phase 3 minimum)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/products` | paginated; filter by active/category/search through a documented JPQL query |
| POST | `/api/v1/products` | validation failure -> documented 400; duplicate SKU -> documented 409 |
| GET | `/api/v1/products/{id}` | missing -> 404 |
| PATCH | `/api/v1/products/{id}` | validated request; locking fields deferred to the Phase 7 lab |
| GET | `/api/v1/categories` | paginated |

## warehouse-service (planned Phase 3 minimum)

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/api/v1/warehouses` | paginated |
| POST | `/api/v1/warehouses` | duplicate code -> 409 |
| GET | `/api/v1/warehouses/{id}` | missing -> 404 |
| PATCH | `/api/v1/warehouses/{id}` | validated request |
| GET | `/api/v1/warehouses/{id}/locations` | paginated; 404 when the warehouse is missing |
| POST | `/api/v1/warehouses/{id}/locations` | validated request |

## Gateway route plan (wired in Phase 5; nothing routed today)

- `/catalog/**` -> `catalog-service`
- `/warehouse/**` -> `warehouse-service`
- Actuator, Eureka and administrative endpoints are not routed publicly.
