(ns cljctools.net.server.api
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [io.pedestal.http :as http]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.jetty.websockets :as pedestal.ws]
   [cljctools.net.core.protocols :as p]
   [cognitect.transit :as transit])
  (:import
   org.eclipse.jetty.websocket.api.Session
   java.io.ByteArrayInputStream
   java.io.ByteArrayOutputStream
   java.net.URI
   java.nio.ByteBuffer))

(defn create-service
  [opts]
  (let [routes #{["/" :get (fn [_] {:body (clojure-version) :status 200}) :route-name :root]
                 ["/echo" :get #(hash-map :body (pr-str %) :status 200) :route-name :echo]}
        {:keys [service-map ws-paths host port]
         :or {port 8080
              host "0.0.0.0"}} opts]
    (merge
     {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
      ::http/routes #(route/expand-routes routes #_(deref #'service/routes))

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
      ::http/resource-path "/public"

              ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
      ::http/type :jetty
      ::http/container-options (if ws-paths
                                 {:context-configurator #(pedestal.ws/add-ws-endpoints % ws-paths)}
                                 {})
      ::http/host host
      ::http/port port}
     {:env :dev
      ::http/join? false
      ::http/allowed-origins {:creds true :allowed-origins (constantly true)}}
     service-map)))

(defn create-channels
  []
  (let [server-ops| (chan 10)
        server-ops|m (mult server-ops|)
        ws-clients-evt| (chan (sliding-buffer 50))
        ws-clients-evt|m (mult ws-clients-evt|)
        ws-clients-data| (chan (sliding-buffer 50))
        ws-clients-data|m (mult ws-clients-data|)]
    {:server-ops| server-ops|
     :server-ops|m server-ops|m
     :ws-clients-evt| ws-clients-evt|
     :ws-clients-evt|m ws-clients-evt|m
     :ws-clients-data| ws-clients-data|
     :ws-clients-data|m ws-clients-data|m}))

; use (.getRemote ws-session) to send directly to one socket
; as it is done in pedestal itself:
; https://github.com/pedestal/pedestal/blob/master/jetty/src/io/pedestal/http/jetty/websockets.clj#L51
; 
; or: need to pass a custom :listener-fn to add-ws-endpoints
; https://github.com/pedestal/pedestal/blob/master/jetty/src/io/pedestal/http/jetty/websockets.clj#L174
; can req response map to session ?

(defn create-proc-server
  [channels ctx opts]
  (let [{:keys [server-ops| server-ops|m ws-clients-evt| ws-clients-data| ws-clients-data|m]} channels
        {:keys [ws? service-map]} opts
        server-ops|t (tap server-ops|m (chan 10))
        ws-clients-data|t (tap ws-clients-data|m (chan 10))
        ws-clients (atom {})
        baos (ByteArrayOutputStream. 4096)
        transit-writer (transit/writer baos :json)
        transit-write (fn [data out|]
                        (transit/write transit-writer data)
                        (put! out| (ByteBuffer/wrap (.toByteArray baos)))
                        (.reset baos)
                        out|)
        transit-read (fn [payload]
                       (let [bais (ByteArrayInputStream. payload)
                             transit-reader (transit/reader bais :json)
                             data (transit/read transit-reader)]
                         data))
        ws-paths {"/ws" {:on-connect (pedestal.ws/start-ws-connection
                                      (fn [ws-session send|]
                                        (println "socket connected")
                                        (put! ws-clients-evt| {:op :ws-client/connect})
                                        (swap! ws-clients assoc ws-session send|)))
                         :on-text (fn [msg]
                                    (put! ws-clients-data| {:op :ws-client/data :data msg}))
                         :on-binary (fn [payload offset length]
                                      (put! ws-clients-data| {:op :ws-client/data
                                                              :data (transit-read payload)}))
                         :on-error (fn [t]
                                     (println "socket error")
                                     (println t)
                                     (put! ws-clients-evt| {:op :ws-client/error :ex t})
                                     #_(log/error :msg "WS Error happened" :exception t))
                         :on-close (fn [num-code reason-text]
                                     (println "socket closed")
                                     (put! ws-clients-evt| {:op :ws-client/close
                                                            :num-code num-code
                                                            :reason reason-text})
                                     #_(log/info :msg "WS Closed:" :reason reason-text))}}
        service (create-service (merge
                                 (when ws? {:ws-paths ws-paths})
                                 opts))
        broadcast (let [{:keys [data]} opts]
                    (doseq [[^Session session send|] @ws-clients]
                      (when (.isOpen session)
                        (transit-write data send|))))
        state (atom {:opts opts
                     :server nil
                     :ws-clients ws-clients})]
    (go
      (loop []
        (when-let [[v port] (alts! [server-ops|t ws-clients-data|t])]
          (condp = port
            server-ops|t (condp = (:op v)
                           :start
                           (let [])

                           :stop
                           (let []))

            ws-clients-data|t (condp = (:op v)
                                :ws-client/data
                                (let []
                                  (println "server received" v)
                                  #_(broadcast {:data v})))))
        (recur))
      (println "; proc-ops go-block exiting"))
    (reify
      p/Start
      (-start [_]
        (let [server (-> service ;; start with production configuration
                         http/default-interceptors
                         http/dev-interceptors
                         http/create-server
                         http/start)]
          (swap! state assoc :server server)))
      (-stop [_]
        (http/stop (:server @state)))
      p/Broadcast
      (-broadcast [_ opts]
        (broadcast opts))
      clojure.lang.ILookup
      (valAt [_ k] (.valAt _ k nil))
      (valAt [_ k not-found] (.valAt @state k not-found)))))

(defn start
  [_]
  (p/-start _))

(defn stop
  [_]
  (p/-stop _))

(defn broadcast
  [_ opts]
  (p/-broadcast _ opts))


