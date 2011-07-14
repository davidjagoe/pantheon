(ns pantheon.component.dispatch-controller.core
  (:import [org.llrp.ltk.net LLRPConnector LLRPEndpoint]
           [org.llrp.ltk.generated.messages RO_ACCESS_REPORT])
  (:require
   [clojure.contrib.io :as io]
   [com.rheosystems.clipper.core :as clipper])
  (:use clojure.set))

;;; Dispatch Controller
;;; -------------------
;;;
;;; 
;;; Pantheon HTTP Namespace
;;; =======================
;;;
;;; /pantheon.component.dispatch-controller/*
;;; 
;;; Interfaces
;;; ==========
;;;
;;; External Interfaces:
;;; 
;;; - Interfaces to an Impinj reader via the clipper library
;;;
;;; - Interfaces to pantheon.component.tag-database to translate tag
;;; - ids to product details.
;;;
;;; - HTTP PUT /ns/shipment-documents
;;;
;;;   Adds a shipment document to the system, and sets the active
;;;   shipment document
;;;
;;; - HTTP PUT /ns/reader/on
;;;
;;;   Starts the RFID reader
;;;
;;; - HTTP PUT /ns/reader/off
;;;
;;;   Stops the RFID reader
;;;
;;; - HTTP GET /ns/reader/status
;;;
;;;   Returns the status of the RFID reader
;;;
;;; - HTTP GET /ns/shipment/status
;;;
;;;   Returns the *shipment status*
;;;

;;; The RFID reader is in a reading state all the time. An active
;;; shipment document defines what *should* be read in the next
;;; TRUCK-MUST-DEPART-WITHIN minutes. Anything read when there is no
;;; active shipment document is regarded as surplus, and any products
;;; unread at the end of the timeout are regarded as missing. If the
;;; tags read exactly match the products on the shipment and the truck
;;; has been clear of the antennae for
;;; WAIT-AFTER-TRUCK-CLEARED-ANTENNA seconds (i.e. we have read no
;;; tags in that time) then the shipment is considered complete. The
;;; notification controller is called, and the active shipment
;;; document is set to nil.

;;; ----------------------------
;;; Constants
;;; ---------

(def TRUCK-MUST-DEPART-WITHIN 5) ;; Minutes
(def WAIT-AFTER-TRUCK-CLEARED-ANTENNA 10) ;; Seconds

;;; ----------------------------
;;; Timers
;;; ------

;;; The departure timer is reset and started when a shipment document
;;; is uploaded
(def departure-timer (ref nil))

;;; The truck clear timer is started as soon as the tags read match
;;; the products expected. We wait a while in case another (stolen)
;;; pallet is yet to be read. If *any* is read this is reset to
;;; WAIT-AFTER-TRUCK-CLEARED-ANTENNA
(def truck-clear-timer (ref nil))

;;; Shipment Document Reference
;;; ---------------------------

;;; The active shipment document is set when a shipment document is
;;; uploaded
(def active-shipment-document (ref nil))

;;; Products Read Reference
;;; -----------------------

;;; The products-read is updated on a callback from the RFID reader;
;;; its value is independent of the ref types above. We only really
;;; need to use this atom if the active-shipment-document is set and
;;; the timer has not run out - otherwise reading a tag is an error
;;; and is handled separately.
(def tags-read (ref nil))

(def sample-shipment-document
     {:shipment-id "12345",
      :orders
      {:order1
       {:customer
        {:name "Fred" :email "david.jagoe@pragmagility.com" :cell "27720198335"}
        :items {:A 2 :B 3 :C 1}}
       :order2
       {:customer
        {:name "Jim"
         :email "jim@example.com" :cell "27769775304"}
        :items {:A 2 :B 1 :C 2}}}})

;;; ----------------------------



(defn shipment-status
  "Returns the name of the current state that exists. The possible
  states are

  :nothing-doing
  :truck-departing
  :missing-tags
  :extra-tags
  :shipment-complete
  :invalid-state

   Every decision-period, we act on the system's *shipment status*.
   This involves determines which of the named states currently
   exists, resetting the state of the system (if appropriate) and also
   taking the supplied action for each

   - :nothing-doing

     No tags read, no shipment expected

   - :truck-departing

     Active shipment, timeout not reached

   - :missing-tags

     Active shipment, timeout reached

   - :extra-tags

     Tags read that are not expected on the active shipment
     document (including the case where there is no active
     shipment document).

   - :shipment-complete

     Tags read exactly match the shipment, and we have read no
     tags for WAIT-AFTER-TRUCK-CLEARED-ANTENNA seconds."
  
  []
  )

(defn parse-shipment [& args]
  sample-shipment-document)

(defn reader-active? []
  true)

(defn receive-shipment-document
  "Whenever we receive a shipment document we take the following steps:

   1. Reset the active-shipment-document and timer refs

   2. Ensure that the reader is active"
  
  []
  {:pre [(reader-active?)]}
  (println @tags-read)
  (let [shipment-details (parse-shipment)] ; (parse-shipment (io/slurp* (request :body)))
    ;; TODO: refuse if there is already an active shipment?
    (dosync
     (alter active-shipment-document (constantly shipment-details))
     (alter departure-timer (constantly TRUCK-MUST-DEPART-WITHIN))
     (alter truck-clear-timer (constantly WAIT-AFTER-TRUCK-CLEARED-ANTENNA))
     (alter tags-read (constantly #{}))))
  nil)

;;; RFID Reader Management
;;; ----------------------

(def handler
     (proxy [Object LLRPEndpoint] []
       ;; TODO: Log error messages
       (errorOccured [msg] nil)
       (messageReceived [msg]
                        (if (= (.getTypeNum msg) RO_ACCESS_REPORT/TYPENUM)
                          (let [tag-ids (set (map #(.. % getEPCParameter getEPC) (. msg getTagReportDataList)))]
                            (dosync
                             (alter tags-read (fn [current] (union current tag-ids)))))))))

(def reader (LLRPConnector. handler "10.2.0.99"))

(defn boot []
  (clipper/start reader))

(defn shutdown []
  (clipper/stop reader))
