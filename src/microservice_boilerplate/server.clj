(ns microservice-boilerplate.server
  (:require [com.stuartsierra.component :as component]
            [microservice-boilerplate.database.traced :as database.traced]
            [microservice-boilerplate.router :as router]
            [microservice-boilerplate.routes :as routes]
            [microservice-boilerplate.sentry :as sentry]
            [parenthesin.components.config.aero :as config]
            [parenthesin.components.db.jdbc-hikari :as database]
            [parenthesin.components.http.clj-http :as http]
            [parenthesin.components.server.reitit-pedestal-jetty :as webserver]
            [parenthesin.helpers.logs :as logs]
            [parenthesin.helpers.migrations :as migrations])
  (:gen-class))

(def system-atom (atom nil))

(defn- build-system-map []
  (component/system-map
   :config (config/new-config)
   :sentry (component/using (sentry/new-sentry) [:config])
   :http (http/new-http)
   :router (component/using (router/new-router routes/routes) [:config])
   :raw-database (component/using (database/new-database) [:config])
   :database (component/using (database.traced/new-traced-database) [:raw-database])
   :webserver (component/using (webserver/new-webserver {:io.pedestal.http/enable-session {}}) [:config :http :router :database :sentry])))

(defn start-system! [system-map]
  (logs/setup :info :auto)
  (migrations/migrate (migrations/configuration-with-db))
  (->> system-map
       component/start
       (reset! system-atom)))

(defn stop-system! []
  (swap!
   system-atom
   (fn [s] (when s (component/stop s)))))

(defn -main
  "The entry-point for 'gen-class'"
  [& _args]
  (start-system! (build-system-map)))

(comment
  (stop-system!))
