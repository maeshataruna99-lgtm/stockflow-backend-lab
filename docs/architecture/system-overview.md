# System overview

Status: Phase 0 design baseline. No code exists yet; names and ports below are
the agreed defaults, checked for consistency across the Phase 0 documents.
Runtime evidence (containers actually register, actually route) first appears
in Phase 4 verification.

## Container view

```text
             client (curl / Bruno / .http)
                          | :8080
                          v
                  api-gateway ----------> discovery-server (Eureka) :8761
                   |        |                ^               ^
     /catalog/**   |        |  /warehouse/** |               |
                   v        v                |               |
      catalog-service :8081     warehouse-service :8082 ----+
       |  catalogdb                 |  warehousedb
       +--------> postgres :5432 <--+   (one instance, two logical
                                        databases, one role each)
                  redis :6379  (cache from Phase 6; never the source of truth)
```

## Services

| Service | Internal port | Responsibility | Owns | Database |
| --- | --- | --- | --- | --- |
| `api-gateway` | 8080 | Single external entry: routing and gateway use cases (Phase 5) | no business data | none |
| `discovery-server` | 8761 | Eureka service registry | no business data | none |
| `catalog-service` | 8081 | Product, SKU, category and unit master data | catalog tables | `catalogdb` |
| `warehouse-service` | 8082 | Warehouse and storage-location master data | warehouse tables | `warehousedb` |
| `postgres` | 5432 (internal only) | Two logical databases with least-privilege roles | - | - |
| `redis` | 6379 (internal only) | Cache; introduced in Phase 6 | cache only | none |

## Request flows

- External read/write: client -> gateway `:8080` -> routed by path prefix to the
  owning service (discovered via Eureka internal DNS) -> the service's own
  logical database.
- Cross-service references use IDs and HTTP APIs only. No cross-database joins,
  no cross-database foreign keys (see
  [ADR 0002](../decisions/0002-data-ownership.md)).
- The gateway owns no business rules and no database tables.

## Notes

- Host port publishing plan: only the gateway is expected to be published on
  the host; postgres and redis stay internal. The final publish list is
  confirmed with evidence in Phase 4.
- Service names and ports in this document are the single source of truth;
  build files and compose files must match them or the phase review fails.
