# Guidebook 30: Horizontal and Vertical Scaling with Resource Limits

## 1. Concept

Two ways to give a service more capacity:
- Vertical scaling: one instance gets more resources (more CPU, more memory). Simple, no change to the architecture, but bounded by the size of one machine and a failure takes everything with it.
- Horizontal scaling: more instances of the same service behind a load balancer. It needs the service to be stateless or to share its state (here: database, Redis), adds the cost of one JVM and one connection pool per instance, and a failure takes one instance. The load balancer must know which instances exist (the registry, guidebook 05).

Neither helps if the bottleneck is elsewhere (the database, a lock, the load generator). Resource limits are what make a scaling experiment mean something: without a CPU limit on a container the instances share the host and the result says little about the instance.

## 2. Implementation

- `compose.scale.yaml` (a third override file, not part of the normal stack): a CPU limit (`cpus`, default 0.5) and a memory limit (`mem_limit`, default 640m, JVM heap set to 65 percent of it with `-XX:MaxRAMPercentage=65`) on every catalog instance, and a third instance `catalog-3` (a copy of `catalog-2`). Limits are overridable with `LAB_SCALE_CPUS` and `LAB_SCALE_MEMORY`.
- `labs/scale/scale-lab.sh`: four scenarios with the same load: A one instance (0.5 CPU), B two instances, C three instances (0.5 CPU each, 1.5 CPU in total), D one instance with 1.5 CPU. Load: 24 workers inside the gateway container loop `GET /api/v1/products?size=50` for 20 s after a 10 s warm-up, going round the instances of the scenario like a round-robin balancer, straight at the services so that the gateway's rate limiter does not cap the result. The script prints the answered requests, `docker stats` in the middle of the run, and the limits as Docker applied them (`docker inspect`). At the end it recreates the catalog without limits.
- Docker host of the measurements: 8 CPUs, 8 GB memory.

## 3. Measured observation

Requests answered with 200 in 20 s (requests per second), no errors in any scenario:

| Scenario | Run 1 (complete) | Run 2 (stopped after B) | Run 3 (complete) |
|---|---|---|---|
| A: 1 instance, 0.5 CPU | 347 (17 req/s) | 361 (18 req/s) | 755 (37 req/s) |
| B: 2 instances, 0.5 CPU each | 858 (42 req/s) | 670 (33 req/s) | 649 (32 req/s) |
| C: 3 instances, 0.5 CPU each | 958 (47 req/s) | not reached | 1182 (59 req/s) |
| D: 1 instance, 1.5 CPU | 957 (47 req/s) | not reached | 1258 (62 req/s) |

(Run 2 was interrupted when the application was closed; its two scenarios are reported, but it is not a complete run, and run 3 repeated everything.)

Resources seen in the middle of the runs (`docker stats`): every 0.5 CPU instance used 46 to 60 percent of one CPU, i.e. it sat at its limit; the 1.5 CPU instance used 147 to 155 percent; memory was 335 to 382 MiB of the 640 MiB limit for every instance. `docker inspect` confirmed the limits Docker applied (`NanoCpus` 500000000 and 1500000000, `Memory` 671088640 bytes).

What the numbers support, and what they do not:
- The instances were CPU bound: each ran at its CPU limit, so the capacity of a scenario follows its total CPU quota, not the number of processes.
- With the same total CPU (1.5), three small instances and one bigger instance gave the same throughput within the noise (47 and 47; 59 and 62).
- In both complete runs the 1.5 CPU scenarios (C, D) were above the 0.5 CPU scenario (A) (47 against 17, 59 against 37).
- The noise is large. The one-instance scenario A ranged from 17 to 37 req/s between runs, and in run 3 A was above B (37 against 32). The ordering of A and B and any claim of "twice the instances, twice the throughput" is not supported. Likely contributors (not isolated): the JVM compiling under a CPU quota during the measurement, CPU throttling, and the load generator (24 `wget` processes) sharing the host.
- Other limits of this lab: one machine (the "instances" share its memory bus and the one PostgreSQL), a read-only list endpoint, no failure injection, three runs of which two are complete (the third was interrupted).

## 4. Reproduction

Start the infrastructure and a baseline (the script needs the gateway container as the load generator):

```bash
docker compose -f compose.yaml -f compose.labs.yaml -f compose.scale.yaml up -d --build postgres redis discovery gateway catalog
```

Run the lab with an admin token (log in as root through the gateway); it takes about 12 minutes because a JVM with 0.5 CPU starts slowly:

```bash
labs/scale/scale-lab.sh <admin-token> 20 24
```

Restore the normal stack afterwards:

```bash
docker compose -f compose.yaml -f compose.labs.yaml up -d
```

## 5. Failure modes and edge cases

- Every extra instance costs a JVM: about 350 MiB of resident memory here, a database connection pool (10 connections each), and a registry entry. With the pool of 10 and `max_connections` of 100, ten instances of one service would already use the whole PostgreSQL limit (guidebook 19).
- A CPU limit is enforced by throttling in time slices, so a short burst can be delayed even when the average is under the limit; latency percentiles, which this lab did not collect, would show it.
- More instances do not help a bottleneck that is shared: all instances use the same PostgreSQL, the same Redis and the same unique-key checks.
- The gateway's load balancer needs a few seconds (and a restart gap, see the open item in the Phase 7 gate) to learn about a new instance.
- Vertical scaling of a JVM needs the heap to follow: `MaxRAMPercentage` makes the heap a share of the container limit.

## 6. Interview questions

- When is horizontal scaling better than vertical scaling, and when is it not?
- Why does a CPU-bound service need a CPU limit in an experiment?
- What shared resource limits the benefit of adding catalog instances?
- Why can a result of "17 requests per second" and "37 requests per second" both come from the same setup?
