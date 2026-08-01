(ns microservice-boilerplate.interceptors.authentication)

(def require-login
  "Route interceptor: redirects anonymous requests to /login instead of
  letting them reach a protected handler. Assoc'ing :response during :enter
  is enough to short-circuit the rest of the interceptor chain -- Pedestal
  stops at the first :enter that produces a ring-shaped response, same
  mechanism the exception/coercion interceptors already rely on."
  {:name ::require-login
   :enter (fn [{:keys [request] :as context}]
            (if (get-in request [:session :user])
              context
              (assoc context :response
                     {:status 302 :headers {"Location" "/login" "Content-Type" "text/plain"}})))})

(defn require-role
  "Route interceptor factory: denies requests whose logged-in user isn't one
  of `allowed-roles`. Must sit after require-login in the interceptor chain
  -- it assumes a session user already exists and only checks the role, so
  an anonymous request 403s here instead of getting the /login redirect
  require-login gives it.
  Usage: (require-role \"admin\") or (require-role \"admin\" \"diretoria\")."
  [& allowed-roles]
  (let [allowed (set allowed-roles)]
    {:name ::require-role
     :enter (fn [{:keys [request] :as context}]
              (if (contains? allowed (get-in request [:session :user :role]))
                context
                (assoc context :response
                       {:status 403 :headers {"Content-Type" "text/plain"} :body "Forbidden"})))}))
