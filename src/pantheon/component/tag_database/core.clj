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

(defn- get-database []
  (fleet/load-persistent "/tmp/pantheon.component.tag-database.fdb"))

(defn put-tag [id document])

(defn get-tag [id]
  (let [db (get-database)
        res (fleet/query db ["select" "tags" {"where" ["=" "id" id]}])]
    (fleet/close db)
    (str res)))
