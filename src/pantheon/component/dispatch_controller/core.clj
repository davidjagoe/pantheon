(ns pantheon.component.dispatch-controller.core)

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
;;; unread at the end of the timeout are regarded as missing.

(def TRUCK-MUST-DEPART-WITHIN 5) ;; Minutes

;;; The active shipment document is set when a shipment document is
;;; uploaded
(def active-shipment-document (ref nil))
;;; The departure timer is reset when a shipment document is uploaded
(def departure-timer (ref nil))

;;; The products-read is updated on a callback from the RFID reader;
;;; its value is independent of the ref types above. We only really
;;; need to use this atom if the active-shipment-document is set and
;;; the timer has not run out - otherwise reading a tag is an error
;;; and is handled separately.
(def products-read (atom nil))

;; (def sample-shipment-document
;;      {:shipment-id "12345",
;;       :orders
;;       {:order1
;;        {:customer
;;         {:name "Fred" :email "david.jagoe@pragmagility.com" :cell "27720198335"}
;;         :items {:A 2 :B 3 :C 1}}
;;        :order2
;;        {:customer
;;         {:name "Jim"
;;          :email "jim@example.com" :cell "27769775304"}
;;         :items {:A 2 :B 1 :C 2}}}})

;;; Every decision-period, we act on the system's *shipment status*.
;;; This involves determines which of the named states currently
;;; exists, resetting the state of the system (if appropriate) and
;;; also taking the supplied action for each
;;;
;;; - :nothing-happening
;;; 
;;;      No tags read, no shipment expected
;;;
;;; - :truck-departing
;;; 
;;;      Active shipment, timeout not reached
;;;
;;; - :missing-tags
;;; 
;;;      Active shipment, timeout reached
;;; 
;;; - :extra-tags
;;;
;;;      Tags read that are not expected on the active shipment
;;;      document (including the case where there is no active
;;;      shipment document)
;;; 
(defn act! []
  
  false)

;;; Whenever we receive a shipment document we take the following
;;; steps:
;;;
;;; 1. (TODO) Ensure that the reader is reading tags, and start it if
;;;    not
;;;
;;; 2. Reset the active-shipment-document and timer refs
;;;

(defn receive-shipment-document [shipment-document]
  (let [shipment-details (parse-shipment (io/slurp* (request :body)))]
    ;; TODO: refuse if there is already an active shipment?
    (dosync
     (alter active-shipment-document (constantly shipment-details))
     (alter departure-timer (constantly TRUCK-MUST-DEPART-WITHIN)))))
