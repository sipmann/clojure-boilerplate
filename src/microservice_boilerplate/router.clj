(ns microservice-boilerplate.router
  (:require [com.stuartsierra.component :as component]
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

(defn- router-settings [env]
  {:exception pretty/exception
   :data {:coercion reitit.schema/coercion
          :muuntaja (m/create
                     (-> m/default-options
                         (assoc-in [:formats "application/json" :decoder-opts :bigdecimals] true)))
          :interceptors [(select-keys (ring-middlewares/session) [:name :enter :leave])
                         (select-keys (ring-middlewares/flash) [:name :enter :leave])
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
