(ns integration.microservice-boilerplate.users-crud-test
  (:require [clojure.string :as string]
            [clojure.test :as clojure.test]
            [com.stuartsierra.component :as component]
            [integration.microservice-boilerplate.util :as util]
            [matcher-combinators.matchers :as matchers]
            [microservice-boilerplate.database.users :as db.users]
            [microservice-boilerplate.router :as router]
            [microservice-boilerplate.routes :as routes]
            [parenthesin.components.config.aero :as components.config]
            [parenthesin.components.db.jdbc-hikari :as components.database]
            [parenthesin.components.http.clj-http :as components.http]
            [parenthesin.components.server.reitit-pedestal-jetty :as components.webserver]
            [parenthesin.helpers.state-flow.server.pedestal :as state-flow.server]
            [schema.test :as schema.test]
            [state-flow.api :as state-flow.api :refer [defflow]]
            [state-flow.assertions.matcher-combinators :refer [match?]]
            [state-flow.core :refer [flow]]
            [state-flow.state :as state]))

(clojure.test/use-fixtures :once schema.test/validate-schemas)

(defn- create-and-start-components! []
  (component/start-system
   (component/system-map
    :config (components.config/new-config)
    :http (components.http/new-http-mock {})
    :router (component/using (router/new-router routes/routes)
                             [:config])
    :database (component/using (components.database/new-database)
                               [:config])
    :webserver (component/using (components.webserver/new-webserver)
                                [:config :http :router :database]))))

;; the session cookie identifies the client to the server; without echoing it
;; back, each request would start a brand new (anonymous) session
(defn- session-cookie [response]
  (some-> (get-in response [:headers "Set-Cookie"])
          first
          (string/split #";")
          first))

;; the anti-forgery token is only ever exposed inside the rendered html, so
;; it has to be scraped from the hidden form field
(defn- csrf-token [response]
  (some-> (:body response)
          (->> (re-find #"name=\"__anti-forgery-token\" type=\"hidden\" value=\"([^\"]+)\""))
          second))

(defn- form-post [uri cookie token body]
  (state-flow.server/request! {:method :post
                               :uri    uri
                               :headers (cond-> {"Content-Type" "application/x-www-form-urlencoded"}
                                          cookie (assoc "Cookie" cookie)
                                          token (assoc "x-csrf-token" token))
                               :body   body}))

(defflow flow-integration-users-crud-test
  {:init (util/start-system! create-and-start-components!)
   :cleanup util/stop-system!
   :fail-fast? true}
  (flow "should create, edit, update and delete a user through the html forms"

    [database (state-flow.api/get-state :database)
     login-page (state-flow.server/request! {:method :get :uri "/login"})
     :let [cookie (session-cookie login-page)]
     _login-response (form-post "/login" cookie (csrf-token login-page)
                                {:email "admin@admin.com" :password "admin"})
     new-page (state-flow.server/request! {:method :get :uri "/users/new" :headers {"Cookie" cookie}})
     :let [token (csrf-token new-page)]
     create-response (form-post "/users" cookie token {:name "Carlos Teste"
                                                       :email "carlos.teste@example.com"
                                                       :password "segredo123"
                                                       :role "user"})
     created (state/invoke #(db.users/user-by-email "carlos.teste@example.com" database))
     after-create-list (state-flow.server/request! {:method :get
                                                    :uri    "/users"
                                                    :headers {"Cookie" cookie}})
     edit-page (state-flow.server/request! {:method :get
                                            :uri    (str "/users/" (:id created) "/edit")
                                            :headers {"Cookie" cookie}})
     :let [edit-token (csrf-token edit-page)]
     update-response (form-post (str "/users/" (:id created)) cookie edit-token
                                {:name "Carlos Atualizado"
                                 :email "carlos.teste@example.com"
                                 :password ""
                                 :role "admin"})
     updated (state/invoke #(db.users/user-by-email "carlos.teste@example.com" database))
     after-update-list (state-flow.server/request! {:method :get
                                                    :uri    "/users"
                                                    :headers {"Cookie" cookie}})
     delete-response (form-post (str "/users/" (:id created) "/delete") cookie edit-token nil)
     deleted (state/invoke #(db.users/user-by-email "carlos.teste@example.com" database))
     after-delete-list (state-flow.server/request! {:method :get
                                                    :uri    "/users"
                                                    :headers {"Cookie" cookie}})]

    (match? (matchers/embeds {:status 302 :headers {"Location" "/users"}})
            create-response)

    (match? {:name "Carlos Teste" :email "carlos.teste@example.com" :role "user"}
            created)

    (match? {:status 200 :body #"Carlos Teste"}
            after-create-list)

    (match? {:status 200 :body #"criado com sucesso"}
            after-create-list)

    (match? {:status 200 :body #"Carlos Teste"}
            edit-page)

    (match? (matchers/embeds {:status 302 :headers {"Location" "/users"}})
            update-response)

    (match? {:name "Carlos Atualizado" :role "admin" :password-hash (:password-hash created)}
            updated)

    (match? {:status 200 :body #"atualizado com sucesso"}
            after-update-list)

    (match? (matchers/embeds {:status 302 :headers {"Location" "/users"}})
            delete-response)

    (match? nil deleted)

    (match? {:status 200 :body #"removido com sucesso"}
            after-delete-list)))

(defflow flow-integration-users-forbidden-for-non-admin-test
  {:init (util/start-system! create-and-start-components!)
   :cleanup util/stop-system!
   :fail-fast? true}
  (flow "a logged-in user without the admin role can't reach /users"

    [admin-login-page (state-flow.server/request! {:method :get :uri "/login"})
     :let [admin-cookie (session-cookie admin-login-page)]
     _admin-login (form-post "/login" admin-cookie (csrf-token admin-login-page)
                             {:email "admin@admin.com" :password "admin"})
     new-page (state-flow.server/request! {:method :get :uri "/users/new" :headers {"Cookie" admin-cookie}})
     _create-response (form-post "/users" admin-cookie (csrf-token new-page)
                                 {:name "Non Admin"
                                  :email "non.admin@example.com"
                                  :password "segredo123"
                                  :role "user"})

     non-admin-login-page (state-flow.server/request! {:method :get :uri "/login"})
     :let [non-admin-cookie (session-cookie non-admin-login-page)]
     _non-admin-login (form-post "/login" non-admin-cookie (csrf-token non-admin-login-page)
                                 {:email "non.admin@example.com" :password "segredo123"})
     users-as-non-admin (state-flow.server/request! {:method :get
                                                     :uri    "/users"
                                                     :headers {"Cookie" non-admin-cookie}})]

    (match? {:status 403}
            users-as-non-admin)))
