(ns eu.stratuslab.cimi.resources.cloud-entry-point-test
  (:require
    [eu.stratuslab.cimi.resources.cloud-entry-point :refer :all]
    [eu.stratuslab.cimi.couchbase-test-utils :as t]
    [clojure.test :refer :all]
    [clojure.data.json :as json]
    [peridot.core :refer :all]))

(use-fixtures :each t/flush-bucket-fixture)

(use-fixtures :once t/temp-bucket-fixture)

(defn ring-app []
  (t/make-ring-app resource-routes))

(deftest lifecycle

  ;; retrieve cloud entry point anonymously
  (-> (session (ring-app))
      (request "/")
      (t/is-status 200)
      (t/is-resource-uri type-uri))

  ;; updating CEP as user should fail
  (-> (session (ring-app))
      (authorize "jane" "user_password")
      (content-type "application/json")
      (request "/"
               :request-method :put
               :body (json/write-str {:name "dummy"}))
      (t/is-status 403))

  ;; update the entry, verify updated doc is returned
  ;; must be done as administrator
  (-> (session (ring-app))
      (authorize "root" "admin_password")
      (content-type "application/json")
      (request "/"
               :request-method :put
               :body (json/write-str {:name "dummy"}))
      (t/is-status 200)
      (t/is-resource-uri type-uri)
      (t/is-key-value :name "dummy"))

  ;; verify that subsequent reads find the right data
  (-> (session (ring-app))
      (request "/")
      (t/is-status 200)
      (t/is-resource-uri type-uri)
      (t/is-key-value :name "dummy")))

(deftest unsupported-methods

  ;; delete is not supported on the CEP
  (-> (session (ring-app))
      (request "/" :request-method :delete)
      (t/is-status 405))

  ;; post is not supported either
  (-> (session (ring-app))
      (request "/"
               :request-method :post
               :body (json/write-str {:name "dummy"}))
      (t/is-status 405)))