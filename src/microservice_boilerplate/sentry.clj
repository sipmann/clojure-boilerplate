(ns microservice-boilerplate.sentry
  (:require [com.stuartsierra.component :as component]
            [parenthesin.helpers.logs :as logs])
  (:import (io.sentry
            Sentry
            SentryOptions
            SpanStatus
            TransactionOptions)))

(defrecord SentryComponent [config]
  component/Lifecycle
  (start [this]
    (let [{:keys [env sentry]} (:config config)
          dsn (:dsn sentry)
          traces-sample-rate (some-> (:traces-sample-rate sentry) str Double/parseDouble)]
      (if dsn
        (do
          (Sentry/init (doto (SentryOptions.)
                         (.setDsn dsn)
                         (.setEnvironment (name env))
                         (.setTracesSampleRate traces-sample-rate)))
          (logs/log :info :sentry :start {:env env :traces-sample-rate traces-sample-rate}))
        (logs/log :info :sentry :skip "SENTRY_DSN not set, error reporting disabled")))
    this)
  (stop [this]
    (Sentry/close)
    this))

(defn new-sentry []
  (map->SentryComponent {}))

(defn capture-exception!
  "Reports `exception` to Sentry. This is the only place in the codebase
  allowed to touch the io.sentry Java classes directly -- everywhere else
  should call this instead of importing io.sentry itself, so the SDK stays
  swappable. No-op when the component never called Sentry/init (no
  SENTRY_DSN configured), since the underlying SDK already handles that."
  [exception]
  (Sentry/captureException exception))

(defn start-transaction!
  "Starts a Sentry performance-monitoring transaction named `name` (e.g. an
  HTTP route template or a job type) for the given `operation` (e.g.
  \"http.server\" or \"queue.task\"), and binds it to the current scope so
  any capture-exception! call made while it's open is attached to the trace.
  Returns a no-op transaction when Sentry was never initialized (no
  SENTRY_DSN), same as capture-exception!."
  [name operation]
  (Sentry/startTransaction name operation (doto (TransactionOptions.)
                                            (.setBindToScope true))))

(defn tag-transaction!
  "Sets a string tag on `transaction` (or any span) for `k` -> `v`, coercing
  `v` to a string. No-op when `v` is nil -- Sentry tags can't be nil, and
  fields like user-id are absent on anonymous requests."
  [transaction k v]
  (when v
    (.setTag transaction k (str v))))

(defn finish-transaction!
  "Finishes `transaction`, marking it OK or INTERNAL_ERROR depending on
  whether `status` (an HTTP status code, may be nil) indicates a server
  error."
  [transaction status]
  (.finish transaction (if (and status (>= status 500))
                         SpanStatus/INTERNAL_ERROR
                         SpanStatus/OK)))

(defn traced-query!
  "Runs `f` (a thunk executing one JDBC statement) inside a Sentry child span
  (op \"db.sql.query\", description `sql`) nested under whatever
  transaction/span is currently bound to scope -- the HTTP/job transactions
  started by start-transaction! above. Runs `f` untraced when there's no
  active span, since Sentry/getSpan returns nil both when Sentry was never
  initialized (no SENTRY_DSN) and when called outside a transaction."
  [sql f]
  (if-let [parent (Sentry/getSpan)]
    (let [span (.startChild parent "db.sql.query" ^String sql)]
      (try
        (let [result (f)]
          (.setStatus span SpanStatus/OK)
          result)
        (catch Exception e
          (.setStatus span SpanStatus/INTERNAL_ERROR)
          (throw e))
        (finally
          (.finish span))))
    (f)))

(defn wrap-job-handler
  "Wraps a background job handler `(fn [job-type payload] ...)` (e.g. a
  proletarian job handler, if this project adds one) so any exception it
  throws is reported to Sentry before being rethrown, and the call is
  wrapped in a Sentry transaction so job duration/throughput show up
  alongside HTTP requests in Performance Monitoring. Mirrors the tracing
  interceptor in router.clj, which does the same for HTTP requests -- job
  workers have no interceptor chain of their own to hook into."
  [handler]
  (fn [job-type payload]
    (let [transaction (start-transaction! (name job-type) "queue.task")]
      (try
        (let [result (handler job-type payload)]
          (finish-transaction! transaction 200)
          result)
        (catch Exception e
          (capture-exception! e)
          (finish-transaction! transaction 500)
          (throw e))))))
