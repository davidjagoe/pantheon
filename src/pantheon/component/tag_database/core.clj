(ns pantheon.component.tag-database.core
  (:require (fleetdb [embedded :as fleet])))

;;; Tag Database
;;; ------------
;;;
;;; Pantheon HTTP Namespace
;;; =======================
;;;
;;; /pantheon.component.tag-database/*
;;; 
;;; Interfaces
;;; ==========
;;;
;;; External Interfaces:
;;; 
;;; - HTTP PUT /pantheon.component.tag-database/tags
;;; 
;;;   add tag data to the database. The HTTP PUT **must** send a
;;;   parameter called "id" which contains the EPC code of the
;;;   tag. Any number of other parameters can also be supplied and
;;;   will be stored in the database as a document, e.g.  {"id":
;;;   "xxxxxxxxx", "product": "ProductA"}
;;;
;;; - HTTP GET /pantheon.component.tag-database/tags/<id>

;;;   query for tag information by id. A json-encoded document is
;;;   returned if such a tag exists in the database, otherwise an null
;;;   value is returned.
;;;
;;; - HTTP DELETE /pantheon.component.tag-database/tags/<id>
;;;
;;;   Removes the identified tag from the database
;;;
;;; Internal Interfaces:
;;; 
;;; - FleetDB via the fleetdb clojure client
;;;

(def DB-FILE "/tmp/pantheon.component.tag-database.fdb")

(defn- load-database []
  (fleet/load-persistent DB-FILE))

(defn put-tag! [tag-document]
  (let [db (load-database)
        res (fleet/query db ["insert" "tags" tag-document])]
    (fleet/close db)
    (str res)))

(defn get-tag
  "Returns json encoded tag document identified by `id`"
  [id]
  (let [db (load-database)
        res (fleet/query db ["select" "tags" {"where" ["=" "id" id]}])]
    (fleet/close db)
    (str res)))

(defn delete-tag!
  "Deletes the identified tag from the database if it exists, else
  does nothing."
  [id]
  (let [db (load-database)
        res (fleet/query db ["delete" "tags" {"where" ["=" "id" id]}])]
    (fleet/close db)
    (str res)))

;;; TODO
;;; ----
;;;
;;; json encode the output of get-tag
;;;
;;; add tests for each function
;;;
