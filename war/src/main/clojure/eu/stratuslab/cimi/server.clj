(ns eu.stratuslab.cimi.server
  "Implementation of the ring application used to create the 
   servlet instance for a web application container."
  (:require
    [clojure.tools.logging :as log]
    [couchbase-clj.client :as cbc]
    [compojure.handler :as handler]
    [eu.stratuslab.cimi.couchbase-cfg :refer [read-cfg]]
    [ring.middleware.format-params :refer [wrap-restful-params]]
    [eu.stratuslab.authn.workflows.authn-workflows :as aw]
    [eu.stratuslab.cimi.cb.bootstrap :refer [bootstrap]]
    [eu.stratuslab.cimi.resources.cloud-entry-point :as cep]
    [eu.stratuslab.cimi.middleware.format-response :refer [wrap-restful-response]]
    [eu.stratuslab.cimi.middleware.cb-client :refer [wrap-cb-client]]
    [eu.stratuslab.cimi.middleware.servlet-request :refer [wrap-servlet-paths wrap-base-uri]]
    [eu.stratuslab.cimi.middleware.couchbase-store :refer [couchbase-store]]
    [eu.stratuslab.cimi.routes :as routes]
    [cemerick.friend :as friend]
    [cemerick.friend.workflows :as workflows]
    [cemerick.friend.credentials :as creds])
  (:import
    [java.net URI]))

(def cb-client-defaults {:uris [(URI/create "http://localhost:8091/pools")]
                         :bucket "default"
                         :username ""
                         :password ""})

(defn- create-cb-client
  "Creates a Couchbase client instance from the given configuration.
   If the argument is nil, then the default connection parameters 
   ('default' bucket on local Couchbase) are used."
  [cb-cfg]
  (if-let [cfg (read-cfg cb-cfg)]
    (try
      (cbc/create-client cfg)
      (catch Exception e
        (log/error "error creating couchbase client: " e)
        (cbc/create-client cb-client-defaults)))
    (do
      (log/warn "using default couchbase configuration")
      (cbc/create-client cb-client-defaults))))

(defn create-ring-handler
  "Creates a ring handler that wraps all of the service routes
   in the necessary ring middleware to handle authentication,
   header treatment, and message formatting."
  [{:keys [cb-client context]}]
  (log/info "creating servlet ring handler with context" context)

  (if-let [workflows (aw/get-workflows cb-client)]
    (-> (friend/authenticate routes/main-routes
                             {:allow-anon? true
                              :login-uri "/login"
                              :default-landing-uri "/webui"
                              :credential-fn (constantly nil)
                              :workflows workflows})
        (handler/site {:session {:store (couchbase-store cb-client)}})
        (wrap-base-uri)
        (wrap-servlet-paths)
        (wrap-cb-client cb-client)
        (wrap-restful-params)
        (wrap-restful-response))
    (do
      (log/error "no authn workflows defined")
      (throw (Exception. "no authn workflows defined")))))

(defn init
  "Creates a shared Couchbase client for the application and
   bootstraps the database.  It returns a map containing the
   service state.  This map must be saved and then provided
   to the destroy function when tearing down the service."
  [{:keys [cb-cfg context]}]

  (log/info "initializing servlet implementation from" cb-cfg)

  (let [cb-params (read-cfg cb-cfg)
        cb-client (create-cb-client cb-params)]
    (bootstrap cb-client)
    {:cb-client cb-client
     :context context}))

(defn destroy
  "Cleans up resources before shutting down the service. The argument
   must be the state map returned from the init function.  This
   allows, for example, the Couchbase client to be cleanly shutdown."
  [{:keys [cb-client]}]
  (log/info "releasing servlet implementation resources")
  (if cb-client
    (cbc/shutdown cb-client 3000)))
