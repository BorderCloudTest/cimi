(ns eu.stratuslab.authn.ldap
  "Functions to allow Friend authentication against an LDAP database."
  (:require
    [clojure.tools.logging :as log]
    [clojure.string :as str]
    [clojure.set :as set]
    [clj-ldap.client :as ldap]
    [clj-schema.schema :refer :all]
    [clj-schema.simple-schemas :refer :all]
    [clj-schema.validation :refer :all]))

(def ^:dynamic *ldap-connection-pool* nil)

(def ldap-params {:host            {:address "localhost" :port 10389}
                  :bind-dn         "cn=admin,o=cloud"
                  :password        "secret"
                  :ssl?            false
                  :connect-timeout 2000
                  :timeout         2000})

(def ^:const ldap-defaults
  {:user-base-dn      "ou=users,o=cloud"
   :user-object-class "inetOrgPerson"
   :user-id-attr      "uid"

   :role-base-dn      "ou=groups,o=cloud"
   :role-object-class "groupOfUniqueNames"
   :role-member-attr  "uniqueMember"
   :role-name-attr    "cn"

   :skip-bind?        false
   })

(def ^:const filter-template "(&(objectClass=%s)(%s=%s))")

(def-map-schema LdapConfigurationHostSchema
                [[:address] NonEmptyString
                 [:port] Integral])

(def-map-schema LdapConfigurationConnectionSchema
                [[:host] LdapConfigurationHostSchema
                 [:ssl?] Boolean
                 (optional-path [:num-connections]) PosIntegral])

(def-map-schema LdapConfigurationCommonSchema
                [[:user-base-dn] NonEmptyString
                 [:user-object-class] NonEmptyString
                 [:user-id-attr] NonEmptyString
                 [:role-base-dn] NonEmptyString
                 [:role-object-class] NonEmptyString
                 [:role-member-attr] NonEmptyString
                 [:role-name-attr] NonEmptyString
                 [:skip-bind?] Boolean])

(def-map-schema LdapConfigurationSchema
                LdapConfigurationCommonSchema
                [[:connection] LdapConfigurationConnectionSchema])

(def-map-schema LdapRequestSchema :loose
                LdapConfigurationCommonSchema
                [[:username] NonEmptyString
                 (optional-path [:password]) NonEmptyString
                 (optional-path [:ldap-connection-pool]) Anything])

(defn connection-pool
  [connection-params]
  (when connection-params
    (try
      (ldap/connect connection-params)
      (catch Exception e
        (log/error "could not initialize ldap connection pool: " (str e))
        nil))))

(defn user-filter
  "create LDAP filter for user records from values for
   :user-object-class, :user-id-attr, and :username"
  [{:keys [user-object-class user-id-attr username]}]
  (if (not-any? str/blank? [user-object-class user-id-attr username])
    (format filter-template user-object-class user-id-attr username)
    (throw (IllegalArgumentException. "user-object-class user-id-attr and username cannot be blank"))))

(defn role-filter
  "create LDAP filter for roles for a particular user DN from
   values for :role-object-class, :role-member-attr, and :user-dn"
  [{:keys [role-object-class role-member-attr user-dn]}]
  (if (not-any? str/blank? [role-object-class role-member-attr user-dn])
    (format filter-template role-object-class role-member-attr user-dn)
    (throw (IllegalArgumentException. "role-object-class role-member-attr and user-dn cannot be blank"))))

(defn user-dn
  [pool {:keys [user-base-dn] :as params}]
  (->>
    (user-filter params)
    (assoc {:attributes [:dn]} :filter)
    (ldap/search pool user-base-dn)
    (map :dn)
    (first)))

(defn roles
  [pool {:keys [role-base-dn role-name-attr] :as params}]
  (->> (role-filter params)
       (assoc {:attributes [role-name-attr]} :filter)
       (ldap/search pool role-base-dn)
       (map (keyword role-name-attr))
       (cons :eu.stratuslab.authn/user)
       (set)))

(defn force-bind
  [pool {:keys [password] :as params}]
  (when-let [dn (user-dn pool params)]
    (ldap/bind? pool dn password)))

(defn authn-map
  [pool params]
  (when-let [dn (user-dn pool params)]
    (let [identity (:username params)
          params (assoc params :user-dn dn)
          r (roles pool params)
          params (assoc params :identity identity :roles r)]
      (select-keys params [:identity :roles :cemerick.friend/workflow]))))

(defn valid-request? [m]
  (when (empty? (validation-errors LdapRequestSchema m))
    (when (or (:skip-bind? m) (:password m))
      m)))

(defn ldap-credential-fn
  [ldap-params cred-map]
  (when (every? map? [ldap-params cred-map])
    (when-let [params (valid-request? (merge ldap-defaults ldap-params cred-map))]
      (let [pool (or (:ldap-connection-pool params) *ldap-connection-pool*)]
        (when (or (:skip-bind? params) (force-bind pool params))
          (authn-map pool params))))))

(defn ldap-cert-credential-fn
  [ldap-params cred-map]
  ;; FIXME: Provide real implementation!
  nil)

