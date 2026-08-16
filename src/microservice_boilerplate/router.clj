(ns microservice-boilerplate.router
  (:require [clojure.string :as str]
            [com.stuartsierra.component :as component]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [microservice-boilerplate.sentry :as sentry]
            [microservice-boilerplate.templates :as templates]
            [muuntaja.core :as m]
            [parenthesin.helpers.logs :as logs]
            [reitit.coercion.schema :as reitit.schema]
            [reitit.dev.pretty :as pretty]
            [reitit.http :as http]
            [reitit.http.coercion :as coercion]
            [reitit.http.interceptors.exception :as exception]
            [reitit.http.interceptors.multipart :as multipart]
            [reitit.http.interceptors.muuntaja :as muuntaja]
            [reitit.http.interceptors.parameters :as parameters]
            [reitit.pedestal :as pedestal]
            [reitit.ring :as ring]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]))

(defn- coercion-error-handler [status]
  (fn [exception _request]
    (logs/log :error exception :coercion-errors (:errors (ex-data exception)))
    ;; only the 500 case is our own bug (we produced a response that doesn't
    ;; match its schema); a 400 is just a client sending a bad request
    (when (= 500 status)
      (sentry/capture-exception! exception))
    {:status status
     :body (if (= 400 status)
             (str "Invalid path or request parameters, with the following errors: "
                  (:errors (ex-data exception)))
             "Error checking path or request parameters.")}))

(defn- stacktrace-str [^Throwable ex]
  (let [sw (java.io.StringWriter.)
        pw (java.io.PrintWriter. sw)]
    (.printStackTrace ex pw)
    (str sw)))

(defn- dev-error-response [request ^Throwable ex]
  (templates/render request "error.html"
                    {:type       (-> ex .getClass .getName)
                     :message    (.getMessage ex)
                     :data       (when (instance? clojure.lang.ExceptionInfo ex)
                                   (pr-str (ex-data ex)))
                     :stacktrace (stacktrace-str ex)}))

(defn- make-exception-info-handler [env]
  (fn [exception request]
    (logs/log :error exception "Server exception:" :exception exception)
    (sentry/capture-exception! exception)
    (if (= env :dev)
      (dev-error-response request exception)
      {:status 500 :body "Internal error."})))

;; Route template (e.g. "/api/users/:id") rather than the raw URI, so
;; requests for the same route group into one Sentry transaction name
;; instead of fragmenting per path-param value.
(defn- transaction-name [request]
  (let [method   (-> request :request-method name str/upper-case)
        template (or (get-in request [:reitit.core/match :template]) (:uri request))]
    (str method " " template)))

;; Wraps the rest of the interceptor chain in a Sentry performance-monitoring
;; transaction. Placed right after session/flash so its :enter runs before
;; (and its :leave after) everything else, including exception-interceptor --
;; by the time :leave runs, exceptions have already been turned into a
;; response, so the transaction always sees a final status. reitit's routing
;; interceptor injects :reitit.core/match into the request before this queue
;; runs, so transaction-name can already read the matched route template.
(defn- tracing-interceptor []
  {:name  ::tracing
   :enter (fn [ctx]
            (let [request (:request ctx)
                  transaction (sentry/start-transaction! (transaction-name request) "http.server")
                  user (get-in request [:session :user])]
              ;; user-id is absent on anonymous routes (/, /login) -- tag-transaction!
              ;; no-ops on nil, so this is safe.
              (sentry/tag-transaction! transaction "user_id" (:id user))
              (assoc ctx ::transaction transaction)))
   :leave (fn [ctx]
            (sentry/finish-transaction! (::transaction ctx) (get-in ctx [:response :status]))
            ctx)})

(defn- router-settings [env]
  {:exception pretty/exception
   :data {:coercion reitit.schema/coercion
          :muuntaja (m/create
                     (-> m/default-options
                         (assoc-in [:formats "application/json" :decoder-opts :bigdecimals] true)))
          :interceptors [(select-keys (ring-middlewares/session) [:name :enter :leave])
                         (select-keys (ring-middlewares/flash) [:name :enter :leave])
                         (tracing-interceptor)
                         swagger/swagger-feature
                         (parameters/parameters-interceptor)
                         (muuntaja/format-negotiate-interceptor)
                         (muuntaja/format-response-interceptor)
                         (exception/exception-interceptor
                          (merge
                           exception/default-handlers
                           {:reitit.coercion/request-coercion  (coercion-error-handler 400)
                            :reitit.coercion/response-coercion (coercion-error-handler 500)
                            clojure.lang.ExceptionInfo         (make-exception-info-handler env)
                            ::exception/default                (make-exception-info-handler env)}))
                         (muuntaja/format-request-interceptor)
                         (coercion/coerce-response-interceptor)
                         (coercion/coerce-request-interceptor)
                         (multipart/multipart-interceptor)]}})

(defn- build-router [routes env]
  (pedestal/routing-interceptor
   (http/router routes (router-settings env))
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path "/"
      :config {:validatorUrl nil
               :operationsSorter "alpha"}})
    (ring/create-resource-handler)
    (ring/create-default-handler))))

(defrecord Router [router config]
  component/Lifecycle
  (start [this]
    (let [env (get-in config [:config :env] :dev)]
      (logs/log :info :router :start {:env env})
      (assoc this :router (build-router (:routes this) env))))
  (stop [this] this))

(defn new-router [routes]
  (map->Router {:routes routes}))
