# WorldsEdgeLink-API

A standalone Spring Boot 4 service that fetches player, leaderboard type, and match statistics data for Age of Empires 1, 2, 3, and 4 from the unofficial WorldsEdge/RLink community API (aoe-api.worldsedgelink.com) and stores it in a local H2 database.

Requirement: Java 25. The Gradle wrapper is included in the repo, no separate install needed.

Run with: `./gradlew bootRun`

On startup the application automatically syncs leaderboard data for all four games. Depending on data volume this can take tens of minutes.

API docs: `http://localhost:8080/swagger-ui/index.html`

H2 console: `http://localhost:8080/h2-console`
JDBC URL: `jdbc:h2:mem:test;LOCK_TIMEOUT=30000;DB_CLOSE_DELAY=-1;MODE=MySQL`
Username: `sa`, password empty.

Sync across games is sequential. Within a single game, leaderboards are processed with limited parallelism through a fixed thread pool (6), but each leaderboard's own pagination is sequential. A semaphore and a fixed delay are used to stay under the external API's rate limit. Overall the system is not concurrent, it is a sequential batch job with a narrow window of parallelism inside it.

Known gap: `LeaderboardController` is not implemented yet, the `/api/leaderboard` endpoint returns empty. Data is written to the database but not exposed.

This project relies on an API that is not supported by the game developer and has been reverse engineered by the community. See github.com/librematch.
