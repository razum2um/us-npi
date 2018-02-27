(ns usnpi.updater
  (:require [usnpi.db :as db]
            [clojure.tools.logging :as log]
            [dk.ative.docjure.spreadsheet :as xls]
            [clj-http.client :as client]
            [hickory.core :as hickory]
            [hickory.select :as s])
  (:import java.util.zip.ZipEntry
           java.util.zip.ZipInputStream))

(def ^:private
  base-url "http://download.cms.gov")

(def ^:private
  path-dl "/nppes/NPI_Files.html")

(defn- parse-page []
  (-> (str base-url path-dl)
      client/get
      :body
      hickory.core/parse
      hickory.core/as-hickory))

(defn- link-selector
  [text]
  (s/and
   (s/tag :a)
   (s/find-in-text (re-pattern text))))

(def ^:private
  deactive-selector
  (link-selector "NPPES Data Dissemination - Monthly Deactivation Update"))

(def ^:private
  dissem-selector
  (link-selector "NPPES Data Dissemination - Weekly Update"))

(defn- fix-href [href]
  (if (= \. (first href))
    (subs href 1)
    href))

(defn- full-url [href]
  (str base-url (fix-href href))  )

(defn- get-deactive-url [htree]
  (when-let [node (first (s/select deactive-selector htree))]
    (let [href (-> node :attrs :href)]
      (full-url href))))


(defn- dl-deact-file []


  )

;; "http://download.cms.gov/nppes/NPPES_Deactivated_NPI_Report_021318.zip"

(defn- dl-deact-file [url]
  (let [resp (client/get url {:as :stream})
        zip-stream (ZipInputStream. (:body resp))
        zip-entry (.getNextEntry zip-stream)
        wb (xls/load-workbook zip-stream)
        sheet (first (xls/sheet-seq wb))
        cell (xls/select-columns {:A :npi} sheet)

        ]

    (take 10 (map :npi (drop 2 cell)))

    )

  )


(defn- read-deact-file [filepath]

  (let [wb (xls/load-workbook filepath)
        sheet (first (xls/sheet-seq wb))
        cell (xls/select-columns {:A :npi} sheet)]
    (mapv :npi (drop 2 cell)))


  )

(defn task []


  :todo)
