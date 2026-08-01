(ns microservice-boilerplate.sentry
  (:require [com.stuartsierra.component :as component]
            [parenthesin.helpers.logs :as logs])
  (:import (io.sentry Sentry SentryOptions)))

(defrecord SentryComponent [config]
  component/Lifecycle
  (start [this]
    (let [{:keys [env sentry]} (:config config)
          dsn (:dsn sentry)]
      (if dsn
        (do
          (Sentry/init (doto (SentryOptions.)
                         (.setDsn dsn)
                         (.setEnvironment (name env))))
          (logs/log :info :sentry :start {:env env}))
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
