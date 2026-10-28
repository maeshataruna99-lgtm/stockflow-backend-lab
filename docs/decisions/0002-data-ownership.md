# ADR 0002 - Data ownership and database boundaries

Status: Accepted (Phase 0). Runtime proof (roles really blocked from each
other's database) is a Phase 2 acceptance check, not a claim in this document.

## Context

Two master-data services run side by side on one local PostgreSQL instance.
Letting them share tables or credentials would blur the service boundary and
defeat later labs (ownership isolation, replication, testing against real
databases).

## Decision

- One PostgreSQL instance, exposed only inside the Docker network, with two
  logical databases:
  - `catalogdb` - used only by `catalog-service`
  - `warehousedb` - used only by `warehouse-service`
- One least-privilege role per service: `catalog_app` (schema owner on
  `catalogdb`) and `warehouse_app` (schema owner on `warehousedb`). Neither
  role can connect to or write the other database. Application connections
  never use the postgres superuser.
- Table ownership:
  - `catalog-service` owns `products`, `skus`, `categories`, `units`.
  - `warehouse-service` owns `warehouses`, `storage_locations`.
- Cross-service references are IDs exchanged over HTTP APIs. No
  cross-database joins, no cross-database foreign keys, no shared migration
  sets, no shared entity classes or shared Java libraries until a concrete
  need is demonstrated.
- The gateway and the discovery server own no tables and enforce no business
  rules.

## Consequences

- Reads that would be a join across services become API calls (or, much
  later, explicitly decided event-fed data).
- Each service's migrations live with that service; a schema change in one
  service never touches the other service's database.
- Phase 2 must prove role isolation against the actual databases before its
  tasks carry VERIFIED.
