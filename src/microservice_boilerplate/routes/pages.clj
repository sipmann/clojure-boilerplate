(ns microservice-boilerplate.routes.pages
  (:require [io.pedestal.http.csrf :as csrf]
            [microservice-boilerplate.interceptors.authentication :as interceptors.authentication]
            [microservice-boilerplate.ports.http-in.pages :as ports.http-in.pages]
            [microservice-boilerplate.schemas.wire-in :as schemas.wire-in]
            [schema.core :as s]))

(def routes
  [["/" {:interceptors [(into {} (csrf/anti-forgery))]
         :get {:summary "root page (redirects to /login or /home)"
               :responses {200 {:body s/Str}
                           500 {:body s/Str}}
               :handler ports.http-in.pages/login}}]

   ["/home" {:get {:summary "home page"
                   :responses {200 {:body s/Str}
                               500 {:body s/Str}}
                   :handler ports.http-in.pages/home}}]

   ["/login" {:interceptors [(into {} (csrf/anti-forgery))]
              :get {:summary "login page"
                    :responses {200 {:body s/Str}
                                500 {:body s/Str}}
                    :handler ports.http-in.pages/login}
              :post {:summary "login page"
                     :parameters {:form schemas.wire-in/LoginForm}
                     :responses {200 {:body s/Str}
                                 500 {:body s/Str}}
                     :handler ports.http-in.pages/do-login}}]

   ["/logout" {:get {:summary "logout page"
                     :responses {200 {:body s/Str}
                                 500 {:body s/Str}}
                     :handler ports.http-in.pages/logout}}]

   ["/users"
    {:interceptors [interceptors.authentication/require-login
                    (interceptors.authentication/require-role "admin")
                    (into {} (csrf/anti-forgery))]}

    ["" {:get {:summary "list all users alphabetically"
               :responses {200 {:body s/Str}
                           500 {:body s/Str}}
               :handler ports.http-in.pages/list-users}
         :post {:summary "create a user"
                :parameters {:form schemas.wire-in/UserForm}
                :responses {200 {:body s/Str}
                            500 {:body s/Str}}
                :handler ports.http-in.pages/create-user!}}]

    ["/new" {:conflicting true
             :get {:summary "new user form"
                   :responses {200 {:body s/Str}
                               500 {:body s/Str}}
                   :handler ports.http-in.pages/new-user-page}}]

    ["/:id/edit" {:get {:summary "edit user form"
                        :parameters {:path {:id s/Int}}
                        :responses {200 {:body s/Str}
                                    404 {:body s/Str}
                                    500 {:body s/Str}}
                        :handler ports.http-in.pages/edit-user-page}}]

    ["/:id" {:conflicting true
             :post {:summary "update a user"
                    :parameters {:path {:id s/Int}
                                 :form schemas.wire-in/UserForm}
                    :responses {404 {:body s/Str}
                                500 {:body s/Str}}
                    :handler ports.http-in.pages/update-user!}}]

    ["/:id/delete" {:post {:summary "delete a user"
                           :parameters {:path {:id s/Int}}
                           :responses {500 {:body s/Str}}
                           :handler ports.http-in.pages/delete-user!}}]]])
