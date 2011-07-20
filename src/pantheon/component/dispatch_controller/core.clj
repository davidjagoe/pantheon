(ns pantheon.component.dispatch-controller.core
  (:import [org.llrp.ltk.net LLRPConnector LLRPEndpoint]
           [org.llrp.ltk.generated.messages RO_ACCESS_REPORT]
           [java.util Timer TimerTask])
  (:require
   [clojure.contrib.io :as io]
   [clojure.contrib.graph :as g]
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

(def ONE-MS 1)
(def ONE-SECOND (* 1000 ONE-MS))
(def ONE-MINUTE (* 60 ONE-SECOND))

(def MONITOR-PERIOD 1) ;; Seconds
(def TRUCK-MUST-DEPART-WITHIN 1) ;; Minutes
(def WAIT-AFTER-TRUCK-CLEARED-ANTENNA 10) ;; Seconds

;;; ----------------------------
;;; Timers
;;; ------

;;; The departure timer is reset and started when a shipment document
;;; is uploaded
(def departure-timer (ref 0 :validator number?))

;;; The truck clear timer is started as soon as the tags read match
;;; the products expected. We wait a while in case another (stolen)
;;; pallet is yet to be read. If *any* is read this is reset to
;;; WAIT-AFTER-TRUCK-CLEARED-ANTENNA
(def truck-clear-timer (ref 0 :validator number?))

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

;;; System States
;;; -------------

(def S-IDLE              :idle)              ; No tags read, no shipment expected
(def S-TRUCK-DEPARTING   :truck-departing)   ; Shipment document received, TRUCK-MUST-DEPART-WITHIN timeout not reached, shipment not complete
(def S-MISSING-TAGS      :missing-tags)      ; Active shipment, shipment not complete, TRUCK-MUST-DEPART-WITHIN timeout reached
(def S-EXTRA-TAGS        :extra-tags)        ; Tags not expected on active shipment document have been read
(def S-SHIPMENT-COMPLETE :shipment-complete) ; Tags read exactly match shipment document, no new tags read for WAIT-AFTER-TRUCK-CLEARED-ANTENNA secs
(def S-INVALID           :invalid)           ; Unexpected combination of ref values; this should never happen

(def valid-states
     #{S-IDLE S-TRUCK-DEPARTING S-MISSING-TAGS
       S-EXTRA-TAGS S-SHIPMENT-COMPLETE S-INVALID})

(def current-state (ref S-IDLE :validator #(some valid-states [%])))

;;; This Directed Graph represents the valid transitions between
;;; states. For example: IDLE -> IDLE is valid (i.e. it can remain
;;; idle); MISSING-TAGS -> MISSING-TAGS invalid (i.e. when it gets in
;;; that state action must be taken immediately and the system put in
;;; a new state); TRUCK-DEPARTING -> IDLE is invalid because the
;;; system must reach one of the 'terminal' states of MISSING-TAGS,
;;; EXTRA-TAGS, or COMPLETE.
(def transitions
     (struct g/directed-graph
             valid-states
             {S-IDLE #{S-IDLE S-TRUCK-DEPARTING S-MISSING-TAGS S-INVALID}
              S-TRUCK-DEPARTING #{S-TRUCK-DEPARTING S-MISSING-TAGS S-EXTRA-TAGS S-SHIPMENT-COMPLETE S-INVALID}
              S-MISSING-TAGS #{S-IDLE S-INVALID}
              S-EXTRA-TAGS #{S-IDLE S-INVALID}
              S-SHIPMENT-COMPLETE #{S-IDLE S-INVALID}
              S-INVALID #{S-IDLE}}))

(defn valid-transition?
  "Returns `to` (i.e. a truthey value) if the transition from state
  `from` to state `to` is valid, otherwise returns nil (i.e. falsey value)."
  [from to]
  (some (set (g/get-neighbors transitions from)) [to]))

;;; --------

;;; For development purposes

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

(defn make-countdown-task [timer-ref]
  (proxy [TimerTask] []
    (run []
         (do
           (println @timer-ref)
           (dosync (alter timer-ref (fn [val] (dec val))))))))

(defn start-countdown [timer-ref period-ms]
  (let [period (clojure.core/long period-ms)]
    (. (Timer.) scheduleAtFixedRate (make-countdown-task timer-ref) period period)))

(defn parse-shipment [& args]
  sample-shipment-document)

(defn reader-active? []
  true)

(defn receive-shipment-document
  "Whenever we receive a shipment document we take the following steps:

   1. Reset the active-shipment-document and timer refs

   2. Ensure that the reader is active"
  
  []
  {:pre [(reader-active?)
         (nil? @active-shipment-document)]}
  (let [shipment-details (parse-shipment)] ; (parse-shipment (io/slurp* (request :body)))
    (dosync
     (alter active-shipment-document (constantly shipment-details))
     (alter departure-timer (constantly TRUCK-MUST-DEPART-WITHIN))
     (alter truck-clear-timer (constantly WAIT-AFTER-TRUCK-CLEARED-ANTENNA))
     (alter tags-read (constantly #{})))
    (start-countdown departure-timer ONE-MINUTE))
  nil)

;;; --------------
;;; Status Monitor
;;; --------------

;;; The status monitor is responsible for periodically inspecing the
;;; shipment status and then acting on the status.

(defn active-shipment-document? [doc]
  (not (nil? doc)))

(defn departure-timeout-reached? [timer-value]
  (<= timer-value 0))

(defn shipment-complete? [shipment-document tags-read]
  (when (> (count tags-read) 5)
    true))

;;; TODO: Pattern matching in clojure? Much more readable than nested
;;; if statements. It is too easy to miss a possible case.
(defn determine-shipment-status
  "Returns a map representing the name of the current status of the
  system. The map contains a key :state and a key :data. The possible
  states are defined above. :data contains the current shipment
  document and the current tags read."
  []
  (let [v-shipment-doc    @active-shipment-document
        v-departure-timer @departure-timer
        v-tags-read       @tags-read]
    (letfn [(build-status [state] {:state state :data {:shipment-document v-shipment-doc :tags-read v-tags-read}})]
      (if (active-shipment-document? v-shipment-doc)
        (if (departure-timeout-reached? v-departure-timer)
          (build-status S-MISSING-TAGS)
          (if (shipment-complete? v-shipment-doc v-tags-read)
            ;; TODO: We actually need another state here that causes the
            ;; system to start the other timer to ensure that no more pallets are coming.
            (build-status S-SHIPMENT-COMPLETE)
            (build-status S-TRUCK-DEPARTING)))
        (if (seq @tags-read)
          (build-status S-EXTRA-TAGS)
          (build-status S-IDLE))))))

;;; TODO: Also reset the RFID reader
(defn system-reset! []
  (dosync
   (alter departure-timer (constantly 0))
   (alter truck-clear-timer (constantly 0))
   (alter active-shipment-document (constantly 0))
   (alter tags-read (constantly 0))
   (alter current-state (constantly S-IDLE))))

(defn log-state-change [from to]
  (if-not (= from to)
    (println (str from " -> " to))))

(defn act-on-missing-tags! [state data & rest]
  (println state)
  (println data)
  (println rest)
  (println (str "Missing tags: " data))
  (system-reset!) ; A bit harsh! Should split system-reset! into hard and soft reset
  )

(defn act-on-extra-tags! [state data])

(defn act-on-shipment-complete! [state data])

(defn act-on-invalid-state! [state data])

(def actions
     {[S-TRUCK-DEPARTING S-MISSING-TAGS] act-on-missing-tags!})

(defn act-on-status
  [{:keys [state data] :as status}]
  {:pre [(map? status)]}
  (if (valid-transition? @current-state state)
    (do
      (log-state-change @current-state state)
      (apply (get actions [@current-state state] (fn [& args] nil)) state data)
      (dosync (alter current-state (constantly state))))
    (system-reset!)))

(defn start-status-monitor []
  (let [period (clojure.core/long (* 1000 MONITOR-PERIOD))]
   (. (Timer.)
      scheduleAtFixedRate
      (proxy [TimerTask] []
        (run [] (act-on-status (determine-shipment-status))))
      period period)))

;;; ----------------------
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

;;; -----------------------------------
;;; Component Management and Monitoring
;;; -----------------------------------

;;; TODO: Proper exception logging.
(defn boot []
  (try
    (clipper/start reader)
    (catch Exception e (println e)))
  (start-status-monitor))

(defn shutdown []
  (clipper/stop reader))

;;; ----------
;;; Monitoring
;;; ----------

;;; Return json documents encoding component status

(defn monitor-component-status []
  nil)

(defn monitor-shipment-status []
  nil)

;;; ----------
;;; HTML Views
;;; ----------

(defn view-shipment-status
  "Displays the current status of the shipment"
  []
  (str @tags-read))
