# microservice-boilerplate
Clojure Microservice Boilerplate: Components, Reitit, Pedestal, Schema, Postgresql and Tests

This project started as a fork of
[parenthesin/microservice-boilerplate](https://github.com/parenthesin/microservice-boilerplate)
and adds server-rendered HTML pages and session-based authentication on top
of the original API-only boilerplate.

## About this example
 - **microservice-boilerplate**: An example of how use the boilerplate, it's a simple btc wallet
that fetch the current btc price in USD and you can check your transaction history, do deposits and withdrawals.
It also ships a small authentication example (login/logout, password hashing, a `users` table) used to demonstrate
the additions below.

- [parenthesin/components](https://github.com/parenthesin/components): Helpers and component wrappers to give a foundation to create new services in clojure,
you can find components for database, http, webserver and tools for db migrations.

Verb | URL                     | Description
-----| ----------------------- | ------------------------------------------------
GET  | /home                   | home page (server-rendered)
GET  | /login                  | login page (server-rendered, CSRF-protected)
POST | /login                  | authenticate and start a session
GET  | /logout                 | clear the session
GET  | /wallet/current-btc-usd | get current btc price in usd
GET  | /wallet/history         | get all wallet entries and current total
POST | /wallet/deposit         | do a deposit in btc in the wallet
POST | /wallet/withdrawal      | do a withdrawal in btc in the wallet if possible

## What this fork adds over the original boilerplate

### Server-rendered pages (Selmer)
- `src/microservice_boilerplate/templates.clj`: a thin wrapper around
  [selmer](https://github.com/yogthos/Selmer)'s `render-file`. It injects
  `:current-user` (from the session) and `:csrf-token`
  (`io.pedestal.http.csrf/anti-forgery-token`) into every template, and
  registers a custom `{% csrf-field %}` tag that renders the hidden CSRF
  input.
- Templates live under `resources/templates/`: `base.html` (layout, navbar,
  flash messages), `home.html`, `authentication/login.html`, and
  `error.html` (a stacktrace page rendered only when `:env :dev`, see
  `router.clj`'s `dev-error-response`).
- Selmer's cache is disabled in dev (`parser/cache-off!`) so templates
  reload on every request without restarting the REPL.

### Session-based authentication
- `controllers/authentication.clj`: `login-page` (redirects to `/home` if a
  session already exists) and `do-login!` (looks up the user by email,
  checks the password hash, and sets `:session {:user ...}` without the
  `password-hash`).
- `security/password.clj`: password hashing/checking with jBCrypt.
- `database/users.clj`: `user-by-email`, a honeysql query against the
  `users` table.
- `adapters/users.clj`: translates between `schemas.db/User` (namespaced
  `:users/...` keys) and `schemas.model/User` (plain keys).
- A migration adds the `users` table (`id`, `email`, `name`,
  `password_hash`, `role`) plus a default seed user.
- `/login` carries `io.pedestal.http.csrf/anti-forgery` as a route-level
  interceptor.

### Extra schema layer: `schemas/model.clj`
On top of `schemas.db` (database row shape) and `schemas.wire-in`/
`wire-out` (HTTP payload shapes), this fork introduces `schemas.model` — a
domain shape decoupled from both the database and the wire format. Adapters
(e.g. `adapters/users.clj`) are responsible only for that `db <-> model`
translation.

## Repl
To open a nrepl
```bash
clj -M:nrepl
# or: bb nrepl
```
To open a nrepl with all test extra-deps on it
```bash
clj -M:test:nrepl
```
To open a nrepl with cider middleware
```bash
clj -M:nrepl:cider
# or: bb cider
```

## Run Tests
To run unit tests inside `./test/unit`
```bash
clj -M:test :unit
```
To run integration tests inside `./test/integration`
```bash
clj -M:test :integration
```
To run all tests inside `./test`
```bash
clj -M:test
# or: bb test
```
To generate a coverage report 
```bash
clj -M:test --plugin kaocha.plugin/cloverage
```

## Lint fix and format

```bash
clj -M:clojure-lsp format
clj -M:clojure-lsp clean-ns
clj -M:clojure-lsp diagnostics
# or: bb format
```

## Migrations
To create a new migration with a name
```bash
clj -M:migratus create migration-name
# or: bb create-migration migration-name
```
To execute all pending migrations
```bash
clj -M:migratus migrate
# or: bb migrate
```
To rollback the latest migration
```bash
clj -M:migratus rollback
# or: bb rollback
```
See [Migratus Usage](https://github.com/yogthos/migratus#usage) for documentation on each command.


## Docker
Start containers with postgres `user: postgres, password: postgres, hostname: db, port: 5432`  
and [pg-admin](http://localhost:5433) `email: pg@pg.cc, password: pg, port: 5433`
```bash
docker-compose -f docker/docker-compose.yml up -d
```
Stop containers
```bash
docker-compose -f docker/docker-compose.yml stop
```

## Running the server
First you need to have the database running, for this you can use the docker command in the step above.

Error reporting and performance monitoring (tracing) via [Sentry](https://sentry.io) are opt-in: set the
`SENTRY_DSN` env var (and optionally `APP_ENV`, already used for other config, to tag the environment) to
turn them on. With no `SENTRY_DSN`, the sentry component stays disabled and nothing is sent anywhere --
this is the default for dev and tests. `SENTRY_TRACES_SAMPLE_RATE` (default `1.0`, i.e. trace everything)
controls what fraction of HTTP requests get traced; lower it if trace volume becomes a cost concern.

### Repl
You can start a repl open and evaluate the file `src/microservice_boilerplate/server.clj` and execute following code:
```clojure
(start-system! (build-system-map))
```

### Command line
```bash
clj -M:dev
# or: bb run
```

### Uberjar
You can generate an uberjar and execute it via java in the terminal:
```bash
# genarate a target/service.jar
clj -T:build uberjar
# or: bb uberjar
# execute it via java
java -jar target/service.jar
```

## Features

### System
- [schema](https://github.com/plumatic/schema) Types and Schemas
- [component](https://github.com/stuartsierra/component) System Lifecycle and Dependencies
- [pedestal](https://github.com/pedestal/pedestal) Http Server
- [reitit](https://github.com/metosin/reitit) Http Routes System 
- [clj-http](https://github.com/dakrone/clj-http) Http Client
- [cheshire](https://github.com/dakrone/cheshire) JSON encoding
- [aero](https://github.com/juxt/aero) Configuration file and enviroment variables manager
- [timbre](https://github.com/ptaoussanis/timbre) Logging library
- [next-jdbc](https://github.com/seancorfield/next-jdbc) JDBC-based layer to access databases
- [hikaricp](https://github.com/brettwooldridge/HikariCP) A solid, high-performance, JDBC connection pool at last
- [honeysql](https://github.com/seancorfield/honeysql) SQL as Clojure data structures
- [selmer](https://github.com/yogthos/Selmer) Django/Jinja-inspired HTML templating
- [jbcrypt](https://github.com/jeremyh/jBCrypt) Password hashing (bcrypt)
- [tools.build](https://github.com/clojure/tools.build) Clojure builds as Clojure programs
- [sentry-java](https://github.com/getsentry/sentry-java) Error tracking and performance monitoring (opt-in via `SENTRY_DSN`)

### Tests & Checks
- [kaocha](https://github.com/lambdaisland/kaocha) Test runner
- [kaocha-cloverage](https://github.com/lambdaisland/kaocha-cloverage) Kaocha plugin for code coverage reports
- [schema-generators](https://github.com/plumatic/schema-generators) Data generation and generative testing
- [state-flow](https://github.com/nubank/state-flow) Testing framework for integration tests
- [matcher-combinators](https://github.com/nubank/matcher-combinators) Assertions in data structures
- [pg-embedded-clj](https://github.com/Bigsy/pg-embedded-clj) Embedded PostgreSQL for integration tests
- [clojure-lsp](https://github.com/clojure-lsp/clojure-lsp/) Code Format, Namespace Check and Diagnosis

## Directory Structure
```
./
├── .clj-kondo -- clj-kondo configuration and classes
├── .lsp -- clojure-lsp configuration
├── .github
│   └── workflows -- Github workflows folder.
├── docker -- docker and docker-compose files for the database
├── docs -- Investigation notes and follow-ups not covered by this README.
├── resources -- Application resources assets folder and configuration files.
│   ├── migrations -- Current database schemas, synced on service startup.
│   └── templates -- Selmer HTML templates (layout, home, login, error page).
├── src -- Library source code and headers.
│   └── microservice_boilerplate
│       ├── adapters -- db <-> model translation (e.g. users).
│       ├── controllers -- request handling logic (e.g. authentication).
│       ├── database -- queries per table/aggregate.
│       ├── ports -- HTTP in/out boundary (route handlers).
│       ├── schemas -- db / model / wire-in / wire-out / types schemas.
│       └── security -- password hashing.
└── test -- Test source code.
    ├── integration -- Integration tests source (uses state-flow).
    │   └── microservice_boilerplate -- Tests for service example (wallet, login).
    └── unit -- Unit tests source (uses clojure.test).
        └── microservice_boilerplate -- Tests for service example (wallet, users adapter).
```

## Related

### Similar Projects
- [vloth/ts-microservice-boilerplate](https://github.com/vloth/ts-microservice-boilerplate)

### Forks
- [parenthesin/microservice-boilerplate-malli](https://github.com/parenthesin/microservice-boilerplate-malli)

### Using this Template
- [rafaeldelboni/super-dice-roll-clj](https://github.com/rafaeldelboni/super-dice-roll-clj)

## License
This is free and unencumbered software released into the public domain.  
For more information, please refer to <http://unlicense.org>
