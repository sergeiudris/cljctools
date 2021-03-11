(ns cljctools.socket.websocket
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >! take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   [cljs.reader :refer [read-string]]
   [goog.string.format]
   [goog.string :refer [format]]
   [clojure.spec.alpha :as s]
   [cljctools.socket.spec :as socket.spec]
   [cljctools.socket.protocols :as socket.protocols]))

(when (exists? js/module)
  (def ws (js/require "ws"))
  (set! js/WebSocket ws)
  #_(set! js/module.exports exports))

(s/def ::url string?)

(s/def ::create-opts-opts (s/keys :req-un [::url]))

(defn create-opts
  [{:keys [:url] :as opts}]
  {:pre [(s/assert ::create-opts-opts opts)]}
  (let []
    {:connect-fn
     (fn [socket]
       (let [{:keys [:evt|
                     :recv|]} @socket
             raw-socket (WebSocket. url #js {})]
         (doto raw-socket
           (.on "open" (fn []
                         (println ::connected)
                         (put! evt| {:op :connected})))
           (.on "close" (fn [code reason]
                          (println ::closed)
                          (put! evt| {:op :closed
                                      :reason reason
                                      :code code})))
           (.on "error" (fn [error]
                          (println ::error)
                          (put! evt| {:op :error
                                      :error error})))
           (.on "message" (fn [data]
                            (put! recv| data))))
         raw-socket))

     :disconnect-fn
     (fn [socket]
       (let [{:keys [:raw-socket]} @socket]
         (.close raw-socket 1000 (str :disconnected))))

     :send-fn
     (fn [socket]
       (let [{:keys [:raw-socket]} @socket]
         (.send raw-socket data)))}))