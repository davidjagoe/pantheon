(ns pantheon.core
  (:require [pantheon.component.tag-database.core :as tag-db])
  (:use
   [ring.util.response :only [redirect]]
   [compojure.core :only [defroutes GET POST ANY PUT]]))

;;; Project Description
;;; -------------------
;;;
;;; Pantheon is a number of independent systems that are being used to
;;; implement an RFID-based asset tracking system. Where possible, the
;;; components interact via HTTP to keep them well decoupled. Each
;;; component can be deployed separately to independent hardware. In
;;; practice this is done via configuration, and all of the source
;;; code is bundled in a single JAR or WAR file.
;;; 
;;;
;;; The following components are available:
;;;
;;; - RFID Reader Controllers, with interfaces available for:
;;; 
;;;   - Impinj speedway (and theoretically other LLRP readers) via the
;;;     open source clipper library
;;;
;;;   - ThingMagic Astra via the proprietary ThingMagic Java API
;;;
;;; - Tagging Controller
;;;
;;;   - Interfaces with System Ceramics LGV system
;;;
;;;   - Interfaces with XXX Tag Applicator
;;;
;;;   - Interfaces with the Tag Database
;;;
;;; - Tag Database
;;;
;;;   - Abstract HTTP interface to any database, currently implemented using 
;;;
;;; - Data Acquisition Controller
;;;
;;;   - Interfaces with the Impinj Reader
;;;
;;;   - May later interface with the weighbridge too
;;;
;;; - Notification Controller
;;;
;;;   - Interfaces via HTTP to SMS server
;;;
;;;   - Interfaces via SMTP to email server
;;;

;;; TODO: Automatically generate routes for each component by the
;;; component registering functions with the core.

(defroutes handler
  ;; Welcome!
  (GET "/" [] (str "Welcome to Pantheon"))
  ;; pantheon.component.tag-database
  (PUT "/pantheon.component.tag-database/tags/put" [tag-id params] (tag-db/put-tag tag-id params))
  (GET "/pantheon.component.tag-database/tags/:id" [id] (tag-db/get-tag id))
  ;; catch-all
  (ANY "/*" [path] (redirect "/")))
