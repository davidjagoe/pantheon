(ns pantheon.component.dispatch-controller.core
  (:import [org.llrp.ltk.net LLRPConnector LLRPEndpoint]
           [org.llrp.ltk.generated.messages RO_ACCESS_REPORT]
           [java.util Timer TimerTask])
  (:require
   [clojure.contrib.io :as io]
   [clojure.contrib.graph :as g]
   [com.rheosystems.clipper.core :as clipper])
  (:use clojure.set
        [pantheon.view.core :only [html-page]]))

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

;;; ---------
;;; Constants
;;; ---------

(def ONE-MS 1)
(def ONE-SECOND (* 1000 ONE-MS))
(def ONE-MINUTE (* 60 ONE-SECOND))

(def MONITOR-PERIOD 1) ; Seconds
(def TRUCK-MUST-DEPART-WITHIN 10) ; Minutes
;; (def WAIT-AFTER-TRUCK-CLEARED-ANTENNA 10) ; Seconds

;;; ------
;;; Timers
;;; ------

;;; A `countdown-timer` simply decrements a starting integer every
;;; `period-ms` it is up to an observer of the timer to take action
;;; when the counter reaches 0 (or any other number). By itself the
;;; counter will just continue counting down.

(defstruct countdown-timer :starting-value :current-value :period-ms :timer)

(defn make-countdown-task
  "Returns a proxy to java.util.TimerTask that will decrement the
  current value of the supplied countdown-timer reference withing a dosync block."
  [r-countdown-timer]
  (proxy [TimerTask] []
    (run []
         (dosync
          (alter r-countdown-timer
                 (fn [countdown-timer]
                   (assoc countdown-timer :current-value
                          (dec (:current-value countdown-timer)))))))))

(defn make-countdown-timer
  "Returns a stopped countdown-timer."
  [starting-value period-ms]
  (struct countdown-timer starting-value starting-value period-ms nil))

;; (defn start-countdown
;;   "Resets and starts the supplied countdown-timer. Must be called in a dosync block."
;;   [r-countdown-timer]
;;   (let [period (clojure.core/long (:period-ms @countdown-timer))
;;         timer (Timer.)
;;         task (make-countdown-task r-countdown-timer)]
;;     (. timer scheduleAtFixedRate task period period)
;;     (alter r-countdown-timer
;;            (fn [countdown-timer]
;;              (assoc countdown-timer :timer timer :current-value (:starting-value countdown-timer))))))

(defn start-countdown
  [countdown-timer r-countdown-timer]
  (let [period (clojure.core/long (:period-ms countdown-timer))
        timer (Timer.)
        task (make-countdown-task r-countdown-timer)]
    (. timer scheduleAtFixedRate task period period)
    (assoc countdown-timer :timer timer)))

(defn timer-reset [countdown-timer]
  (. (:timer countdown-timer) cancel)
  (assoc countdown-timer :timer nil :current-value (:starting-value countdown-timer)))

;;; Started when we receive a shipment document
;; (def departure-timer (ref 0 :validator number?))
(def departure-timer (ref (make-countdown-timer TRUCK-MUST-DEPART-WITHIN ONE-SECOND) :validator map?))

;;; Started as soon as the tags read match the products expected. We
;;; wait a while in case another (stolen!)  pallet is yet to be
;;; read. If *any* tags are read this is reset to
;;; WAIT-AFTER-TRUCK-CLEARED-ANTENNA
;; (def truck-clear-timer (ref 0 :validator number?))

;;; -----------------------------
;;; Shipment Document & Tags Read
;;; -----------------------------

(defn- map-or-nil? [thing]
  (or (nil? thing) (map? thing)))

(defn- set-or-nil? [thing]
  (or (nil? thing) (set? thing)))

;;; Set when a shipment document is uploaded from SAP
(def active-shipment-document (ref nil :validator map-or-nil?))

;;; Updated on a callback from the RFID reader
(def tags-read (ref nil :validator set-or-nil?))

;;; ---------------------------
;;; System States & Transitions
;;; ---------------------------

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

;;; ------------------------
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

;;; ------------------------

;; (defn make-countdown-task [timer-ref]
;;   (proxy [TimerTask] []
;;     (run []
;;          (do
;;            (println @timer-ref)
;;            (dosync (alter timer-ref (fn [val] (dec val))))))))

;; (defn start-countdown [timer-ref period-ms]
;;   (let [period (clojure.core/long period-ms)]
;;     (. (Timer.) scheduleAtFixedRate (make-countdown-task timer-ref) period period)))

(defn parse-shipment [& args]
  sample-shipment-document)

(defn reader-active? []
  true)

(defn receive-shipment-document
  "Receive, parse and set the active shipment document; reset and
  start the truck departure timer and reset the tags read reference."
  [] {:pre [(reader-active?)
            (nil? @active-shipment-document)]}
  (let [shipment-details (parse-shipment)] ; (parse-shipment (io/slurp* (request :body)))
    (dosync
     (alter active-shipment-document (constantly shipment-details))
     (alter departure-timer start-countdown departure-timer)
     (alter tags-read (constantly #{}))))
  nil)

;;; --------------
;;; Status Monitor
;;; --------------

;;; The status monitor is responsible for periodically inspecing the
;;; shipment status and then acting on the status.

(defn active-shipment-document? [doc]
  (not (nil? doc)))

(defn timed-out? [timer]
  (<= (:current-value timer) 0))

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
        (if (timed-out? v-departure-timer)
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
(defn soft-reset []
  (dosync
   (alter departure-timer timer-reset)
   (alter active-shipment-document (constantly nil))
   (alter tags-read (constantly nil))
   (alter current-state (constantly S-IDLE))))

;;; TODO
(defn reset-reader [])

(defn hard-reset []
  (do
    (reset-reader)
    (soft-reset)))

(defn log-state-change [from to]
  (if-not (= from to)
    (println (str from " -> " to))))

(defn act-on-missing-tags! [state data]
  (println "Queuing missing tags notification message")
  (soft-reset))

(defn act-on-extra-tags! [state data]
  (println "Queuing extra tags notification message"))

(defn act-on-shipment-complete! [state data]
  (println "Queuing shipment complete message"))

(defn act-on-invalid-state! [state data]
  (do
    (println "Queuing invalid state message")
    (println (str "Invalid state! " state data))
    (hard-reset)))

(def actions
     {[S-TRUCK-DEPARTING S-MISSING-TAGS] act-on-missing-tags!})

(def do-nothing (constantly nil))

(defn get-action [from to]
  (cond
   (= to S-MISSING-TAGS)                    act-on-missing-tags!
   (= to S-EXTRA-TAGS)                      act-on-extra-tags!
   (= to S-IDLE)                            do-nothing
   (= [from to] [S-IDLE S-TRUCK-DEPARTING]) do-nothing
   (= to from)                              do-nothing
   :default (fn [& args] (println (str "No action implemented for state change " from " -> " to)))))

(defn act-on-status
  [{:keys [state data] :as status}]
  {:pre [(map? status)]}
  (if (valid-transition? @current-state state)
    (do
      (log-state-change @current-state state)
      (apply (get-action @current-state state) [state data])
      (dosync (alter current-state (constantly state))))
    (hard-reset)))

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
  (html-page
   [:h1 "Dispatch Status"]
   [:table
    [:thead
     [:tr [:th "Key"] [:th "Value"]]]
    [:tbody
     [:tr [:td "Status"] [:td (str (name @current-state))]]
     [:tr [:td "Shipment Document"] [:td (str @active-shipment-document)]]
     [:tr [:td "Tags Read"] [:td (str @tags-read)]]
     [:tr [:td "Departure Timer"] [:td (str (:current-value @departure-timer))]]
     ]
    ]))
