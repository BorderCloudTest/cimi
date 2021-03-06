;
; Copyright 2013 Centre National de la Recherche Scientifique (CNRS)
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
(ns eu.stratuslab.cimi.resources.job
  "Utilities for managing the CRUD features for jobs."
  (:require
    [couchbase-clj.client :as cbc]
    [couchbase-clj.query :as cbq]
    [eu.stratuslab.cimi.resources.schema :as schema]
    [eu.stratuslab.cimi.resources.utils :as u]
    [eu.stratuslab.cimi.resources.auth-utils :as a]
    [eu.stratuslab.cimi.cb.views :as views]
    [compojure.core :refer [defroutes let-routes GET POST PUT DELETE ANY]]
    [ring.util.response :as r]
    [clojure.walk :as w]
    [clojure.tools.logging :as log]))

(def ^:const resource-type "Job")

(def ^:const collection-resource-type "JobCollection")

(def ^:const type-uri (str "http://schemas.dmtf.org/cimi/1/" resource-type))

(def ^:const collection-type-uri (str "http://schemas.dmtf.org/cimi/1/" collection-resource-type))

(def ^:const base-uri (str "/" resource-type))

(def collection-acl {:owner {:principal "::ADMIN" :type "ROLE"}
                     :rules [{:principal "::USER" :type "ROLE" :right "VIEW"}]})

;; FIXME: This ACL should depend on who is accessing the object
(def job-acl {:owner {:principal "::ADMIN" :type "ROLE"}})

(def validate (u/create-validation-fn schema/Job))

(defn uuid->uri
  "Convert a uuid into the URI for a MachineConfiguration resource.
   The URI must not have a leading slash."
  [uuid]
  (str resource-type "/" uuid))

(defn set-timestamp
  "Copies the updated timestamp to the timeOfStatusChange field."
  [{:keys [updated] :as entry}]
  (assoc entry :timeOfStatusChange updated))

(defn add-cops
  "Adds the collection operations to the given resource."
  [resource]
  (if (a/can-modify? collection-acl)
    (let [ops [{:rel (:add schema/action-uri) :href base-uri}]]
      (assoc resource :operations ops))
    resource))

(defn add-rops
  "Adds the resource operations to the given resource."
  [resource]
  (let [href (:id resource)
        ops [{:rel (:edit schema/action-uri) :href href}
             {:rel (:delete schema/action-uri) :href href}
             {:rel (:stop schema/action-uri) :href (str href "/stop")}]]
    (assoc resource :operations ops)))

(defn create
  "Creates a new job and adds it to the database.  Unlike the add function
   this returns just the job URI (or nil if there is an error).  This is 
   useful when creating jobs in the process of manipulating other resources."
  [cb-client entry]
  (let [uri (uuid->uri (u/create-uuid))
        entry (-> entry
                  (u/strip-service-attrs)
                  (merge {:id uri
                          :resourceURI type-uri
                          :state "QUEUED"
                          :progress 0})
                  (u/set-time-attributes)
                  (set-timestamp)
                  (validate))]
    (if (cbc/add-json cb-client uri entry)
      uri)))

(defn add
  "Add a new Job to the database."
  [cb-client entry]
  (if-let [uri (create cb-client entry)]
    (r/created uri)
    (r/status (r/response (str "cannot create job")) 400)))

(defn retrieve
  "Returns the data associated with the requested Job
   entry (identified by the uuid)."
  [cb-client uuid]
  (if-let [json (cbc/get-json cb-client (uuid->uri uuid))]
    (r/response (add-rops json))
    (r/not-found nil)))

;; FIXME: Implementation should use CAS functions to avoid update conflicts.
(defn edit
  "Updates the given resource with the new information.  This will 
   validate the new entry before updating it."
  [cb-client uuid entry]
  (let [uri (uuid->uri uuid)]
    (if-let [current (cbc/get-json cb-client uri)]
      (let [updated (->> entry
                         (u/strip-service-attrs)
                         (merge current)
                         (u/set-time-attributes)
                         (add-rops)
                         (validate))]
        (if (cbc/set-json cb-client uri updated)
          (r/response updated)
          (r/status (r/response nil) 409))) ;; conflict
      (r/not-found nil))))

(defn delete
  "Deletes the named machine configuration."
  [cb-client uuid]
  (if (cbc/delete cb-client (uuid->uri uuid))
    (r/response nil)
    (r/not-found nil)))

(defn action
  "Perform an action on the given resource."
  [cb-client uuid action params]
  (r/created (uuid->uri uuid)))

(defn query
  "Searches the database for resources of this type, taking into
   account the given options."
  [cb-client & [opts]]
  (let [q (cbq/create-query (merge {:include-docs true
                                    :key type-uri
                                    :limit 100
                                    :stale false
                                    :on-error :continue}
                                   opts))
        v (views/get-view cb-client :resource-uri)

        configs (->> (cbc/query cb-client v q)
                     (map cbc/view-doc-json)
                     (map add-rops))
        collection (add-cops {:resourceURI collection-type-uri
                              :id base-uri
                              :count (count configs)})]
    (r/response (if (empty? configs)
                  collection
                  (assoc collection :jobs configs)))))

(defn value-as-string
  "Converts non-collection values to a string. "
  [v]
  (if (coll? v)
    v
    (if (or (keyword? v) (symbol? v))
      (name v)
      (str v))))

(defn properties-map
  "Converts the given map into a properties map, with all keys
   and values converted into strings.  If the map is nil or 
   empty, then an empty map is returned."
  [props]
  (if (seq props)
    {:properties (w/prewalk value-as-string props)}
    {}))

(defn launch
  "Creates a new job, returning an 'accepted' ring response with
   the CIMI-Job-URI header set.  If the optional props arguments is
   given then the values will be used for job properties.  All of the
   keys and values in the properties will by transformed to strings."
  [cb-client uri action & [props]]
  (let [job-map (merge
                  {:acl job-acl :targetResource uri :action action}
                  (properties-map props))]
    (if-let [job-resp (add cb-client job-map)]
      (let [job-uri (get-in job-resp [:headers "Location"])]
        (-> (r/response nil)
            (r/status 202)
            (r/header "CIMI-Job-URI" job-uri)))
      (-> (str "cannot create job [" action ", " uri "]")
          (r/response)
          (r/status 500)))))

(defroutes collection-routes
           (POST base-uri {:keys [cb-client body]}
                 (if (a/can-modify? collection-acl)
                   (let [json (u/body->json body)]
                     (add cb-client json))
                   (u/unauthorized)))
           (GET base-uri {:keys [cb-client body]}
                (if (a/can-view? collection-acl)
                  (let [json (u/body->json body)]
                    (query cb-client json))
                  (u/unauthorized)))
           (ANY base-uri {}
                (u/bad-method)))


(def resource-routes
  (let-routes [uri (str base-uri "/:uuid")]
              (GET uri [uuid :as {cb-client :cb-client}]
                   (retrieve cb-client uuid))
              (PUT uri [uuid :as {cb-client :cb-client body :body}]
                   (let [json (u/body->json body)]
                     (edit cb-client uuid json)))
              (DELETE uri [uuid :as {cb-client :cb-client}]
                      (delete cb-client uuid))
              (POST (str uri "/:action") [uuid action :as {:keys [cb-client body]}]
                    (let [json (u/body->json body)]
                      (action cb-client uuid action json)))
              (ANY uri {}
                   (u/bad-method))))

(defroutes routes
           collection-routes
           resource-routes)

