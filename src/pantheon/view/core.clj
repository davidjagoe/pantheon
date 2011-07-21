(ns pantheon.view.core
  (:use
   [hiccup.core :only [html]]
   [hiccup.page-helpers :only [doctype xhtml-tag]]))

(defn html-page [& content]
  (html
   (doctype :xhtml-strict)
   (xhtml-tag
    "en"
    [:head
     [:meta {:http-equiv "Content-type" :content "text/html; charset=utf-8"}]
     [:title "Pantheon | RFID Systems"]]
    [:body content])))
