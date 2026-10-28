# Telegram simulation

Created 2026-10-28. Local fixture derives from the official Telegram Bot API contract; owner mock search skipped because Postman is unauthenticated. No cloud workspace created.

## Purpose

Run the real Spring service and HTTP client against Telegram-shaped responses, including downloads, retry_after, blocked users and HTML proxy errors. Fixture control endpoints are local test helpers, not Telegram methods. See `postman/mocks/telegram/fixture.js`.

## Execution

Start `postman simulation run postman/simulations/telegram.sim.yaml --port 4901 --no-history --output ndjson`, then start the test PostgreSQL database and run `./mvnw test`. Tests configure the client's base URL and use the TEST token. Stop the simulation with Ctrl+C.

## Assumptions

- (observed 2026-10-28) Windows sandbox requires permission for Postman's own dataset directory and loopback sockets; without it the CLI reports dataset access denied before listening.
- (observed 2026-10-28) Local PostgreSQL must run in a persistent process/session, not a short-lived sandbox's child process. Test database must be named eduping_test.
- (reasoned 2026-10-28, fixture.js) Responses verify only modeled paths. Real Telegram permissions, exact media behavior and BotFather configuration remain unverified. Delivery acknowledgements can be lost after Telegram accepts a message; fixture does not prove exactly-once delivery.

## Observations

<!-- begin:observations -->
2026-10-28: telegram member served getUpdates, sendMessage, getFile and binary download traffic during the passing PostgreSQL integration suite. 403/429/500/502 responses reached the real HTTP adapter. No members were silent.
<!-- end:observations -->

## What didn't work

- 2026-10-28: first database start's process disappeared after its tool session exited; a foreground persistent PostgreSQL session fixed connection refused. This was local test setup, not a bot failure.
