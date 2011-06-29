(defproject pantheon "0.0.1"
  :description "Pantheon RFID Systems"
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.clojure/clojure-contrib "1.2.0"]
                 [compojure "0.6.4"]
                 [ring/ring-core "0.3.10"]
                 [ring/ring-servlet "0.3.10"]
                 [hiccup "0.3.0"]
                 [fleetdb "0.3.1"]
                 [fleetdb-client "0.2.2"]]
  :dev-dependencies [[ring/ring-jetty-adapter "0.3.10"]
                     [swank-clojure "1.4.0-SNAPSHOT"]
                     [ring/ring-devel "0.3.10"]
                     [lein-ring "0.4.4"]
                     [marginalia "0.5.0"]]
  :ring {:handler pantheon.core/handler})
