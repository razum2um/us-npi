(ns usnpi.npi
  (:require [usnpi.db :as db]
            [clojure.string :as str]
            [usnpi.http :as http]))

;;
;; Helpers
;;

(defn- sanitize [x] (str/replace x #"[^a-zA-Z0-9]" ""))

(defn- parse-ids
  [ids-str]
  (not-empty (re-seq #"\d+" ids-str)))

(defn- parse-words
  [term]
  (not-empty
   (as-> term $
     (str/split $ #"\s+")
     (remove empty? $))))

(defn- to-str
  [x]
  (if (keyword? x) (name x) (str x)))

(defn- as-bundle
  "Composes a Bundle JSON response from a list of JSON strings."
  [models]
  (format "{\"entry\": [%s]}" (str/join ",\n" (map :resource models))))

(defn- gen-search-expression
  [fields]
  (->>
   fields
   (map (fn [[pr & pth]]
          (format "'%s:' || coalesce((resource#>>'{%s}'), '')"
                  (to-str pr) (str/join "," (mapv to-str pth)))))
   (str/join " || ' ' || \n")))

;;
;; Practitioner
;;

(def ^:private
  search-expression
  (gen-search-expression
   [[:g :name 0 :given 0]
    [:g :name 0 :given 1]
    [:m :name 0 :middle 0]
    [:p :name 0 :prefix 0]
    [:z :name 0 :siffix 0]
    [:f :name 0 :family]

    [:g :name 1 :given 0]
    [:g :name 1 :given 1]
    [:m :name 1 :middle 0]
    [:p :name 1 :prefix 0]
    [:z :name 1 :siffix 0]
    [:f :name 1 :family]

    [:s :address 0 :state]
    [:c :address 0 :city]]))

(def trgrm_idx
  (format "CREATE INDEX IF NOT EXISTS pract_trgm_idx ON practitioner USING GIST ((\n%s\n) gist_trgm_ops);"
          search-expression))

(def to-resource-expr "(resource || jsonb_build_object('id', id, 'resourceType', 'Practitioner'))")

(def practitioner-by-id-sql
  (format " select %s::text as resource from practitioner where not deleted and id = ? " to-resource-expr))

(defn get-practitioner [{{npi :npi} :route-params :as req}]
  (if-let [pr (:resource (first (db/query [practitioner-by-id-sql npi])))]
    (http/set-json (http/http-resp pr))
    (http/err-resp 404 "Practitioner with id = %s not found." npi)))

(defn get-practitioners-query [{q :q cnt :_count}]
  (let [cond (cond-> []
               q (into (->> (str/split q #"\s+")
                            (remove str/blank?)
                            (mapv #(format "%s ilike '%%%s%%'" search-expression %)))))]
    (format "
select jsonb_build_object('entry', jsonb_agg(row_to_json(x.*)))::text as bundle
from (select %s as resource from practitioner where not deleted %s limit %s) x"
            to-resource-expr
            (if (not (empty? cond))
              (str "\nAND\n" (str/join "\nAND\n" cond))
              "")
            (or cnt "100"))))

(defn get-pracitioners [{params :params :as req}]
  (let [q (get-practitioners-query params)
        prs (db/query [q])]
    (http/set-json (http/http-resp (:bundle (first prs))))))

(defn get-practitioners-by-ids [{params :params :as req}]
  (if-let [ids  (:ids params)]
    (let [sql (format "select %s as resource from practitioner where not deleted and id in (%s)"
                      to-resource-expr
                      (->> (str/split ids #"\s*,\s*")
                           (mapv (fn [id] (str "'" (sanitize id) "'")))
                           (str/join ",")))]
      (http/set-json (http/http-resp (as-bundle (db/query sql)))))
    (http/err-resp 400 "Parameter ids is malformed.")))

;;
;; Organizations
;;

(def ^:private
  search-expression-org
  (gen-search-expression
   [[:n :name]
    [:s :address 0 :state]
    [:c :address 0 :city]]))

(defn get-organization
  "Returns a single organization entity by its id."
  [request]
  (let [npi (-> request :route-params :npi)
        q {:select [#sql/raw "resource::text"]
           :from [:organizations]
           :where [:and [:not :deleted] [:= :id npi]]}]
    (if-let [row (first (db/query (db/to-sql q)))]
      (http/set-json (http/http-resp (:resource row)))
      (http/err-resp 404 "Organization with id = %s not found." npi))))

(defn get-organizations-by-ids
  "Returns multiple organization entities by their ids."
  [request]
  (if-let [ids (some->> request :params :ids parse-ids)]
    (let [q {:select [#sql/raw "resource::text"]
             :from [:organizations]
             :where [:and [:not :deleted] [:in :id ids]]}
          orgs (db/query (db/to-sql q))]
      (http/set-json (http/http-resp (as-bundle orgs))))
    (http/err-resp 400 "Parameter ids is malformed.")))

(defn get-organizations
  "Returns multiple organization entities by a query term."
  [request]
  (let [words (some-> request :params :q parse-words)
        limit (-> request :params :_count (or 100))

        get-raw #(db/raw (format "\n%s ilike '%%%s%%'" search-expression-org %))

        q {:select [#sql/raw "resource::text"]
           :from [:organizations]
           :where [:and [:not :deleted]]
           :limit limit}

        q (if (empty? words)
            q
            (update q :where concat (map get-raw words)))

        orgs (db/query (db/to-sql q))]

    (http/set-json (http/http-resp (as-bundle orgs)))))
