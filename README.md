# WorldsEdgeLink-API

A standalone Spring Boot 4 service that fetches player, leaderboard type, and match statistics data for Age of Empires 1, 2, 3, and 4 from the unofficial WorldsEdge/RLink community API (aoe-api.worldsedgelink.com) and stores it in a local H2 database.

Requirement: Java 25. The Gradle wrapper is included in the repo, no separate install needed.

Run with: `./gradlew bootRun`

On startup the application automatically syncs leaderboard data for all four games. Depending on data volume this can take tens of minutes. See [Concurrency Model & Design Rationale](#concurrency-model--design-rationale) below for why.

API docs: `http://localhost:8080/swagger-ui/index.html`

H2 console: `http://localhost:8080/h2-console`
JDBC URL: `jdbc:h2:mem:test;LOCK_TIMEOUT=30000;DB_CLOSE_DELAY=-1;MODE=MySQL`
Username: `sa`, password empty.

Known gap: `LeaderboardController` is not implemented yet, the `/api/leaderboard` endpoint returns empty. Data is written to the database but not exposed.

This project relies on an API that is not supported by the game developer and has been reverse engineered by the community. See github.com/librematch.

## Concurrency Model & Design Rationale

This service does not use an aggressive, fully-parallelized sync strategy.

The current implementation is:

- **Sequential across games.** One game's leaderboards fully sync before the next game starts.
- **Bounded parallelism within a game.** A fixed thread pool (size 6) processes leaderboards concurrently, but each leaderboard's own pagination stays sequential.
- **Rate-limited.** A semaphore plus a fixed delay keep requests well under the external API's limits.

### Why not go faster?

`aoe-api.worldsedgelink.com` is an unofficial, community-reverse-engineered API (see [librematch](https://github.com/librematch)). It is not provisioned for high-throughput third-party traffic, and every request competes for the same limited rate budget the whole community relies on.

A highly concurrent sync would risk stricter rate limiting for everyone, with little real benefit: this is a background sync job, not a user-facing hot path. A sync that takes tens of minutes is a non-issue for a local, self-hosted service.

The slower design is a deliberate trade-off. Throughput is sacrificed for the long-term stability of a shared, unofficial API.

### Faster implementation

A significantly faster, consistent concurrency approach, approaching the mathematical limits of throughput for this workload, has been developed and tested. It is kept private and is not part of this public repository, to avoid enabling higher-volume traffic against the unofficial upstream API.