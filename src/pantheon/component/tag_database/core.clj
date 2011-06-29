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
;;; - FleetDB via the fleetdb clojure client
;;;
;;; - HTTP PUT interface to add tag data to the database
;;;
;;; - HTTP GET interface to query for tag information by id
;;;

(def DB-FILE "/tmp/pantheon.component.tag-database.fdb")

(defn- init-database []
  (fleet/init-persistent DB-FILE))

(defn- load-database []
  (fleet/load-persistent DB-FILE))

(defn put-tag [req]
  (println req)
  (let [tag-document (:params req)
        db (init-database)
        res (fleet/query db ["insert" "tags" tag-document])]
    (fleet/close db)
    (str res)))

(defn get-tag [id]
  (let [db (load-database)
        res (fleet/query db ["select" "tags" {"where" ["=" "id" id]}])]
    (fleet/close db)
    (str res)))

;;; TODO
;;; ----
;;;
;;; - Handle duplicate tag id (update? exception? email?)