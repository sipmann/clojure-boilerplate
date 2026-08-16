(ns microservice-boilerplate.database.traced
  "Wraps the raw Hikari database component so every query it runs shows up as
  a Sentry span, nested under the HTTP transaction that's already open --
  see sentry/traced-query!. Sits at the :database key in server.clj's system
  map (the raw parenthesin component moves to :raw-database), so every
  database/*.clj namespace gets this for free through the same
  components.database/execute call they already make."
  (:require
   [com.stuartsierra.component :as component]
   [microservice-boilerplate.sentry :as sentry]
   [next.jdbc :as jdbc]
   [parenthesin.components.db.jdbc-hikari :as components.database]))

(defrecord TracedDatabase [raw-database datasource]
  component/Lifecycle
  (start [this]
    ;; exposing :datasource too (not just execute) keeps this a transparent
    ;; drop-in for the raw component, in case future code reaches for it
    ;; directly the way this project's job workers might someday.
    (assoc this :datasource (:datasource raw-database)))
  (stop [this] this)

  components.database/DatabaseProvider
  (execute [_ sql-params]
    (sentry/traced-query! (first sql-params)
                          #(jdbc/execute! datasource sql-params)))
  (execute [_ sql-params opts]
    (sentry/traced-query! (first sql-params)
                          #(jdbc/execute! datasource sql-params opts))))

(defn new-traced-database []
  (map->TracedDatabase {}))
