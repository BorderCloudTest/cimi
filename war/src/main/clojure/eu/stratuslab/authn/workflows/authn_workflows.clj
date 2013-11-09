(ns eu.stratuslab.authn.workflows.authn-workflows
  (:require
    [clojure.tools.logging :as log]
    [clojure.pprint :refer [pprint]]
    [couchbase-clj.client :as cbc]
    [cemerick.friend :as friend]
    [cemerick.friend.util :as futil]
    [cemerick.friend.workflows :as workflows]
    [cemerick.friend.credentials :as creds]
    [ring.util.request :as req]
    [eu.stratuslab.cimi.resources.utils :as u]
    [eu.stratuslab.authn.workflows.client-certificate :as cwf]))

(defn form-workflow
  "Creates a friend login workflow that redirects to a login page and
   then processes a POST to the same page with credentials.  Unlike the
   standard interactive form workflow, this workflow also processes
   certificate-based credentials.

   The credential map provided to the defined credential function may
   contain :username, :password, and :ssl-client-cert-chain keys."
  [& {:keys [credential-fn login-failure-handler redirect-on-auth?] :as form-config
      :or   {redirect-on-auth? true}}]
  (fn [{:keys [request-method params form-params ssl-client-cert-chain] :as request}]
    (let [req-auth-config (::friend/auth-config request)]
      (when (and (= :post request-method)
                 (= (futil/gets :login-uri form-config req-auth-config) (req/path-info request)))
        (let [creds {:username              (get form-params "username" "")
                     :password              (:password params)
                     :ssl-client-cert-chain ssl-client-cert-chain}
              credential-fn (futil/gets :credential-fn form-config req-auth-config)]
          (if-let [user-record (credential-fn
                                 (with-meta creds {::friend/workflow :form-workflow}))]
            (workflows/make-auth user-record
                                 {::friend/workflow          :form-workflow
                                  ::friend/redirect-on-auth? redirect-on-auth?})
            ((or
               (futil/gets :login-failure-handler form-config req-auth-config)
               #'workflows/interactive-login-redirect)
             (update-in request [::friend/auth-config] merge form-config))))))))

(defn cb-lookup-user
  "Looks up a user record (map) given a particular key.  This key
   can be the user's identity or any given alternative names, for
   example, an X500 DN."
  [cb-client userkey]
  (when userkey
    (if-let [user-map (u/user-record cb-client userkey)]
      (do
        (log/info "user record for" userkey "found; active?" (:active user-map))
        (if (:active user-map)
          user-map))
      (log/info "user record for" userkey "NOT found"))))

(defn cb-user-by-id-fn
  "Returns a function that returns a user record with a
   document id of 'User/identity' in the database.  If
   the document doesn't exist or the :active flag is not
   set, then the function returns nil."
  [cb-client]
  (fn [identity]
    (cb-lookup-user cb-client identity)))

(defn cb-user-by-dn-fn
  "Returns a function that retrieves a user record in the
   Couchbase database corresponding to the DN of the subject
   in the given X509 certificate chain.  This method should be
   called with a map with the key :ssl-client-cert containing
   the certificate chain."
  [cb-client]
  (fn [{:keys [ssl-client-cert-chain]}]
    (let [dn (cwf/extract-subject-dn ssl-client-cert-chain)]
      (cb-lookup-user cb-client dn))))

(defn cert-workflow
  "Returns a workflow that tests the client certificate provided
   with the request.  The certificate will have been validated by
   the SSL interactions before the workflow receives the certificate."
  [cb-client]
  (->> cb-client
       (cb-user-by-dn-fn)
       (form-workflow :credential-fn)))

(defn password-workflow
  "Returns the an interactive form workflow for friend that
   is configured to find user information in Couchbase."
  [cb-client]
  (->> cb-client
       (cb-user-by-id-fn)
       (partial creds/bcrypt-credential-fn)
       (form-workflow :credential-fn)))

(defn get-workflows
  "Returns a list of the active workflows for authenticating
   users for the cloud instance."
  [cb-client]
  [(password-workflow cb-client)
   (cert-workflow cb-client)])

