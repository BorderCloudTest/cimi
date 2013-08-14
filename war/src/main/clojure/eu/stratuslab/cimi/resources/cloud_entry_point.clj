(ns eu.stratuslab.cimi.resources.cloud-entry-point
  "Root resource for CIMI, providing information about the locations
  of other resources within the server."
  (:require 
    [clojure.tools.logging :as log]
    [clojure.set :as set]
    [couchbase-clj.client :as cbc]
    [eu.stratuslab.cimi.resources.common :as common]
    [eu.stratuslab.cimi.resources.utils :as utils]
    [clojure.tools.logging :refer [debug info warn]]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [compojure.handler :as handler]
    [compojure.response :as response]
    [clojure.data.json :as json])
  (:import [java.io InputStreamReader]))

(def ^:const resource-type "CloudEntryPoint")

(def ^:const resource-uri (str "http://schemas.dmtf.org/cimi/1/" resource-type))

(def ^:const resource-base-url "/")

(def cep-attributes
  "These are the attributes specific to a CloudEntryPoint."
  #{:baseURI :resourceMetadata
    :systems :systemTemplates
    :machines :machineTemplates :machineConfigs :machineImages
    :credentials :credentialTemplates
    :volumes :volumeTemplates :volumeConfigs :volumeImages
    :networks :networkTemplates :networkConfigs
    :networkPorts :networkPortTemplates :networkPortConfigs
    :addresses :addressTemplates
    :forwardingGroups :forwardingGroupTemplates
    :jobs
    :meter :meterTemplates :meterConfigs
    :eventLogs :eventLogTemplates})

(defn create
  "Creates a new CloudEntryPoint from the given data.  This normally only occurs
   during the service bootstrap process when the database has not yet been 
   initialized."
  [cb-client]
  
  (let [record (->> 
                 {:id resource-base-url
                  :name resource-type
                  :description "StratusLab Cloud"
                  :resourceURI resource-uri}
                 (utils/set-time-attributes))]
    (cbc/add-json cb-client resource-base-url record {:observe true
    		  	    		      	      :persist :master
						      :replicate :zero})))

(defn retrieve
  "Returns the data associated with the CloudEntryPoint.  There is
  exactly one such entry in the database.  The identifier is the root
  resource name '/'.  The baseURI must be passed as this is taken from 
  the ring request."
  [req]
  (let [baseURI (:base-uri req)
        cb-client (:cb-client req)
        doc (cbc/get-json cb-client resource-base-url)]
    (assoc doc :baseURI baseURI)))

(defn list-all
  "Returns the raw list of CloudEntryPoint entries.  There should
   only ever be one of these."
  [{:keys [cb-client]}]
  (println resource-uri))

(defn update
  "Update the cloud entry point attributes.  Note that only the common
  resource attributes can be updated.  The active resource collections
  cannot be changed.  For correct behavior, the cloud entry point must
  have been previously initialized.  Returns nil."
  [req]
  (let [cb-client (:cb-client req)
        body (InputStreamReader. (:body req))
        json (json/read body :key-fn keyword)
        update (->> json
                 (utils/strip-service-attrs)
                 (utils/set-time-attributes))
        current (cbc/get-json cb-client resource-base-url)
        newdoc (merge current update)]
    (log/info "json:" json)
    (log/info "update:" update)
    (log/info "updating CloudEntryPoint:" newdoc)
    (cbc/set-json cb-client resource-base-url newdoc)))

(defroutes resource-routes
  (GET resource-base-url {:as req} {:body (retrieve req)})
  (PUT resource-base-url {:as req} (update req) {}))
