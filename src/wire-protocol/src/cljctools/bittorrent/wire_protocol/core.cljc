(ns cljctools.bittorrent.wire-protocol.core
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string]
   [clojure.spec.alpha :as s]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.bittorrent.bencode.core :as bencode.core]
   [cljctools.bittorrent.spec :as bittorrent.spec]
   [clojure.walk :refer [keywordize-keys]]))


(defprotocol WireProtocol)

(s/def ::wire-protocol #(and
                         (satisfies? WireProtocol %)
                         #?(:clj (satisfies? clojure.lang.IDeref %))
                         #?(:cljs (satisfies? cljs.core/IDeref %))))

(s/def ::channel #?(:clj #(instance? clojure.core.async.impl.channels.ManyToManyChannel %)
                    :cljs #(instance? cljs.core.async.impl.channels/ManyToManyChannel %)))

(s/def ::recv| ::channel)
(s/def ::send| ::channel)
(s/def ::ex| ::channel)
(s/def ::metadata| ::channel)

#_(defprotocol BufferCut
  (cut* [_ recv| expected-size]))

#_(defn buffer-cut
    []
    (let [buffersV (volatile! (transient []))
          total-sizeV (volatile! 0)]
      (reify
        BufferCut
        (cut*
          [_ recv| expected-size]
          (go
            (loop []
              (let [total-size @total-sizeV]
                (cond

                  (== total-size expected-size)
                  (let [resultB (if (== 1 (count @buffersV))
                                  (nth @buffersV 0)
                                  (->
                                   @buffersV
                                   (persistent!)
                                   (bytes.core/concat)))]
                    (vreset! buffersV (transient []))
                    (vreset! total-sizeV 0)
                    resultB)

                  (> total-size expected-size)
                  (let [overB (bytes.core/concat (persistent! @buffersV))
                        resultB (bytes.core/buffer-wrap overB 0 expected-size)
                        leftoverB (bytes.core/buffer-wrap overB expected-size (- total-size expected-size))]
                    (vreset! buffersV (transient [leftoverB]))
                    (vreset! total-sizeV (bytes.core/size leftoverB))
                    resultB)

                  :else
                  (when-let [recvB (<! recv|)]
                    (vswap! buffersV conj! recvB)
                    (vreset! total-sizeV (+ total-size (bytes.core/size recvB)))
                    (recur))))))))))

(defn buffer-cut
  [{:as opts
    :keys [::from|
           ::expected-size|
           ::to|
           ::metadata|
           :close?]}]
  (go
    (loop [buffersT (transient [])
           total-size 0
           expected-size (<! expected-size|)]
      (when expected-size
        (cond
          (== total-size expected-size)
          (let [resultB (if (== 1 (count buffersT))
                          (nth buffersT 0)
                          (->
                           buffersT
                           (persistent!)
                           (bytes.core/concat)))]
            (>! to| resultB)
            (recur (transient []) 0 (<! expected-size|)))

          (> total-size expected-size)
          (let [overB (bytes.core/concat (persistent! buffersT))
                resultB (bytes.core/buffer-wrap overB 0 expected-size)
                leftoverB (bytes.core/buffer-wrap overB expected-size (- total-size expected-size))]
            (>! to| resultB)
            (recur (transient [leftoverB]) (bytes.core/size leftoverB) (<! expected-size|)))

          :else
          (when-let [recvB (<! from|)]
            (recur (conj! buffersT recvB) (+ total-size (bytes.core/size recvB)) expected-size)))))
    (close! to|)))

(def pstrB (-> (bytes.core/byte-array [19]) (bytes.core/buffer-wrap)))
(def protocolB (-> (bytes.core/to-byte-array "\u0013BitTorrent protocol") (bytes.core/buffer-wrap)))
(def reservedB (-> (bytes.core/byte-array [0 0 0 0 0 2r00010000 0 2r00000001]) (bytes.core/buffer-wrap)))
(def keep-alive-byte-arr (bytes.core/byte-array [0 0 0 0]))
(def choke-byte-arr (bytes.core/byte-array [0 0 0 1 0]))
(def unchoke-byte-arr (bytes.core/byte-array [0 0 0 1 1]))
(def interested-byte-arr (bytes.core/byte-array [0 0 0 1 2]))
(def not-interested-byte-arr (bytes.core/byte-array [0 0 0 1 3]))
(def have-byte-arr (bytes.core/byte-array [0 0 0 5 4]))
(def port-byte-arr (bytes.core/byte-array [0 0 0 3 9 0 0]))

(def ^:const ut-metadata-block-size 16384)

(defn extended-msg
  [ext-msg-id data]
  (let [payloadBA (->
                   data
                   (bencode.core/encode))
        msg-lengthB (bytes.core/byte-buffer 4)
        msg-length (+ 2 (bytes.core/alength payloadBA))]
    (bytes.core/put-int msg-lengthB 0 msg-length)
    (->
     (bytes.core/concat
      [(bytes.core/to-byte-array msg-lengthB)
       (bytes.core/byte-array [20 ext-msg-id])
       payloadBA])
     (bytes.core/buffer-wrap))))

(defn handshake-msg
  [infohashB peer-idB]
  (bytes.core/concat [pstrB protocolB reservedB infohashB peer-idB]))

(s/def ::create-wire-opts
  (s/keys :req [::send|
                ::recv|
                ::bittorrent.spec/infohashB
                ::bittorrent.spec/peer-idB]
          :opt [::ex|]))

(defn create-wire-protocol
  [{:as opts
    :keys [::send|
           ::recv|
           ::metadata|
           ::bittorrent.spec/infohashB
           ::bittorrent.spec/peer-idB]}]
  {:pre [(s/assert ::create-wire-opts opts)]
   :post [(s/assert ::wire-protocol %)]}
  (let [stateV (volatile!
                {})

        ex| (chan 1)
        op| (chan 100)

        expected-size| (chan 1)
        cut| (chan 1)

        wire-protocol
        ^{:type ::wire-protocol}
        (reify
          WireProtocol
          #?@(:clj
              [clojure.lang.IDeref
               (deref [_] @stateV)]
              :cljs
              [cljs.core/IDeref
               (-deref [_] @stateV)]))

        release (fn []
                  (close! expected-size|))]

    (buffer-cut {::from| recv|
                 ::expected-size| expected-size|
                 ::to| cut|
                 :close? true})

    (take! ex|
           (fn [ex]
             (release)
             (when-let [ex| (::ex| opts)]
               (put! ex| ex))))

    (go
      (try

        (>! send| (handshake-msg infohashB peer-idB))

        (loop [stateT (transient
                       {:expected-size 1
                        :op :pstrlen
                        :pstrlen nil
                        :msg-length nil
                        :am-choking? true
                        :am-interested? false
                        :peer-choking? true
                        :peer-interested? false
                        :peer-extended? false
                        :peer-dht? false
                        :extensions {"ut_metadata" 3}
                        :peer-extended-data {}
                        :ut-metadata-downloaded 0
                        :ut-metadata-pieces (transient [])})]
          (>! expected-size| (:expected-size stateT))
          (when-let [msgB (<! cut|)]

            (condp = (:op stateT)

              :pstrlen
              (let [pstrlen (bytes.core/get-byte msgB 0)]
                (recur (-> stateT
                           (assoc! :op :handshake)
                           (assoc! :pstrlen pstrlen)
                           (assoc! :expected-size (+ 48 pstrlen)))))

              :handshake
              (let [{:keys [pstrlen]} stateT
                    pstr (-> (bytes.core/buffer-wrap msgB 0 pstrlen) (bytes.core/to-string))]
                (if-not (= pstr "BitTorrent protocol")
                  (throw (ex-info "Peer's protocol is not 'BitTorrent protocol'"  {:pstr pstr} nil))
                  (let [reservedB (bytes.core/buffer-wrap msgB pstrlen 8)
                        infohashB (bytes.core/buffer-wrap msgB (+ pstrlen 8) 20)
                        peer-idB (bytes.core/buffer-wrap msgB (+ pstrlen 28) 20)]
                    (>! send| (extended-msg 0 {:m (:extensions stateT)
                                               :metadata_size 0}))
                    (recur (-> stateT
                               (assoc! :op :msg-length)
                               (assoc! :expected-size 4)
                               (assoc! :peer-extended? (boolean (bit-and (bytes.core/get-byte reservedB 5) 2r00010000)))
                               (assoc! :peer-dht? (boolean (bit-and (bytes.core/get-byte reservedB 7) 2r00000001))))))))

              :msg-length
              (let [msg-length (bytes.core/get-int msgB 0)]
                (if (== 0 msg-length) #_:keep-alive
                    (do
                      (recur stateT))
                    (recur (-> stateT
                               (assoc! :op :msg)
                               (assoc! :msg-length msg-length)
                               (assoc! :expected-size msg-length)))))

              :msg
              (let [stateT (-> stateT
                               (assoc! :op :msg-length)
                               (assoc! :expected-size 4))
                    {:keys [msg-length]} stateT
                    msg-id (bytes.core/get-byte msgB 0)]

                (cond

                  #_:choke
                  (and (== msg-id 0) (== msg-length 1))
                  (recur stateT)

                  #_:unchoke
                  (and (== msg-id 1) (== msg-length 1))
                  (recur stateT)

                  #_:interested
                  (and (== msg-id 2) (== msg-length 1))
                  (recur stateT)

                  #_:not-interested
                  (and (== msg-id 3) (== msg-length 1))
                  (recur stateT)

                  #_:have
                  (and (== msg-id 4) (== msg-length 5))
                  (let [piece-index (bytes.core/get-int msgB 1)]
                    (recur stateT))

                  #_:bitfield
                  (== msg-id 5)
                  (recur stateT)

                  #_:request
                  (and (== msg-id 6) (== msg-length 13))
                  (let [index (bytes.core/get-int msgB 1)
                        begin (bytes.core/get-int msgB 5)
                        length (bytes.core/get-int msgB 9)]
                    (recur stateT))

                  #_:piece
                  (== msg-id 7)
                  (let [index (bytes.core/get-int msgB 1)
                        begin (bytes.core/get-int msgB 5)
                        blockB (bytes.core/buffer-wrap (bytes.core/to-byte-array msgB) 9 (- msg-length 9))]
                    (recur stateT))

                  #_:cancel
                  (and (== msg-id 8) (== msg-length 13))
                  (recur stateT)

                  #_:port
                  (and (== msg-id 9) (== msg-length 3))
                  (recur stateT)

                  #_:extended
                  (and (== msg-id 20))
                  (let [ext-msg-id (bytes.core/get-byte msgB 1)
                        payloadB (bytes.core/buffer-wrap msgB 2 (- msg-length 2))]
                    (cond

                      #_:handshake
                      (== ext-msg-id 0)
                      (let [data (bencode.core/decode (bytes.core/to-byte-array payloadB))]
                        #_(let  [ut-metadata-id (get-in data ["m" "ut_metadata"])]
                            (when ut-metadata-id
                              (let []
                                (>! send| (extended-msg ut-metadata-id {:msg_type 0
                                                                        :piece 0})))))
                        (recur (-> stateT
                                   (assoc! :peer-extended-data data))))

                      (= ext-msg-id 3 #_(get-in stateT [:extensions "ut-metadata"]) #_(get-in stateT [:peer-extended-data "m" "ut_metadata"]))
                      (let [payload-str (bytes.core/to-string payloadB)
                            block-index (-> (clojure.string/index-of payload-str "ee") (+ 2))
                            data-str (subs payload-str 0 block-index)
                            data  (-> data-str (bytes.core/to-byte-array) (bencode.core/decode) (keywordize-keys))]
                        (condp == (:msg_type data)

                          #_:request
                          0
                          (let []
                            (when-let [ut-metadata-id (get-in stateT [:peer-extended-data "m" "ut_metadata"])]
                              (>! send| (extended-msg ut-metadata-id {:msg_type 2
                                                                      :piece (get data "piece")})))
                            (recur stateT))

                          #_:data
                          1
                          (let [block-str (subs payload-str block-index)
                                blockBA (bytes.core/to-byte-array block-str)
                                ut-metadata-size (get-in stateT [:peer-extended-data "metadata_size"])
                                downloaded (+ (:ut-metadata-downloaded stateT) (bytes.core/alength blockBA))]
                            (cond
                              (== downloaded ut-metadata-size)
                              (let [metadataBA (bytes.core/concat (conj! (:ut-metadata-pieces stateT) blockBA))]
                                (>! metadata| metadataBA)
                                (recur (-> stateT
                                           (assoc! :ut-metadata-downloaded 0)
                                           (assoc! :ut-metadata-pieces (transient [])))))

                              (>= downloaded ut-metadata-size)
                              (let []
                                (put! ex| (ex-info "downloaded metadata size is larger than declared" {} nil))
                                (recur (-> stateT
                                           (assoc! :ut-metadata-downloaded 0)
                                           (assoc! :ut-metadata-pieces (transient [])))))

                              :else
                              (let [ut-metadata-id (get-in stateT [:peer-extended-data "m" "ut_metadata"])
                                    downloaded-pieces (/ downloaded ut-metadata-block-size)
                                    next-piece (+ downloaded-pieces 1)]
                                (>! send| (extended-msg ut-metadata-id {:msg_type 0
                                                                        :piece next-piece}))
                                (recur (-> stateT
                                           (assoc! :ut-metadata-downloaded downloaded)
                                           (assoc! :ut-metadata-pieces (conj! (:ut-metadata-pieces stateT) blockBA)))))))

                          #_:reject
                          2
                          (let []
                            (put! ex| (ex-info "metadata request rejected" {} nil))
                            (recur stateT))

                          (println [::unsupported-ut-metadata-msg :ext-msg-id ext-msg-id])))

                      :else
                      (let []
                        (println [::unsupported-extension-msg :ext-msg-id ext-msg-id])
                        (recur stateT))))

                  :else
                  (let []
                    (println [::unknown-message :msg-id msg-id :msg-length msg-length])
                    (recur stateT)))))))

        (catch #?(:clj Exception :cljs :default) ex (put! ex| ex)))
      (release))

    wire-protocol))



(comment

  clj -Sdeps '{:deps {org.clojure/clojure {:mvn/version "1.10.3"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire-protocol"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools/bytes-jvm {:local/root "./cljctools/src/bytes-jvm"}
                      github.cljctools/core-jvm {:local/root "./cljctools/src/core-jvm"}}}'
  
  clj -Sdeps '{:deps {org.clojure/clojurescript {:mvn/version "1.10.844"}
                      org.clojure/core.async {:mvn/version "1.3.618"}
                      github.cljctools.bittorrent/bencode {:local/root "./bittorrent/src/bencode"}
                      github.cljctools.bittorrent/wire {:local/root "./bittorrent/src/wire-protocol"}
                      github.cljctools.bittorrent/spec {:local/root "./bittorrent/src/spec"}
                      github.cljctools/bytes-js {:local/root "./cljctools/src/bytes-js"}
                      github.cljctools/bytes-meta {:local/root "./cljctools/src/bytes-meta"}
                      github.cljctools/core-js {:local/root "./cljctools/src/core-js"}}}' \
   -M -m cljs.main --repl-env node --compile cljctools.bittorrent.wire-protocol.core --repl
  
  (do
    (require '[clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close! onto-chan!
                                                pub sub unsub mult tap untap mix admix unmix pipe
                                                timeout to-chan  sliding-buffer dropping-buffer
                                                pipeline pipeline-async]])
    
    (require '[cljctools.bytes.core :as bytes.core] :reload)
    (require '[cljctools.bittorrent.bencode.core :as bencode.core] :reload)
    (require '[cljctools.bittorrent.wire-protocol.core :as wire-protocol.core] :reload))
  ;
  )

(comment


  (bytes.core/get-int (bytes.core/buffer-wrap (bytes.core/byte-array [0 0 0 5])) 0)
  (bytes.core/get-int (bytes.core/buffer-wrap (bytes.core/byte-array [0 0 1 3])) 0)


  ; The bit selected for the extension protocol is bit 20 from the right (counting starts at 0) . 
  ; So (reserved_byte [5] & 0x10) is the expression to use for checking if the client supports extended messaging
  (bit-and 2r00010000  0x10)
  ; => 16

  (->
   (bytes.core/byte-buffer 4)
   (bytes.core/put-int 0 16384)
   (bytes.core/get-int 0))

  (let [byte-buf  (bytes.core/byte-buffer 4)
        _ (bytes.core/put-int byte-buf 0 16384)
        byte-arr (bytes.core/to-byte-array byte-buf)]
    [(bytes.core/alength byte-arr)
     (-> byte-arr
         (bytes.core/buffer-wrap)
         (bytes.core/get-int 0))])


  ;
  )


(comment

  (time
   (doseq [i (range 10000)
           j (range 10000)]
     (== i j)))
  ; "Elapsed time: 1230.363084 msecs"

  (time
   (doseq [i (range 10000)
           j (range 10000)]
     (= i j)))
  ; "Elapsed time: 3089.990067 msecs"

  ;
  )


(comment

  (do
    (time
     (let [kword :foo/bar]
       (dotimes [i 100000000]
         (= kword :foo/bar))))
    ; "Elapsed time: 191.077891 msecs"

    (time
     (let [kword :foo/bar]
       (dotimes [i 100000000]
         (identical? kword :foo/bar))))
    ; "Elapsed time: 96.919884 msecs"
    )


  ;
  )


(comment

  (do
    (time
     (let [x (atom (transient []))]
       (dotimes [i 10000000]
         (swap! x conj! i))
       (count (persistent! @x))))
    ;"Elapsed time: 684.808948 msecs"

    (time
     (let [x (volatile! (transient []))]
       (dotimes [i 10000000]
         (vswap! x conj! i))
       (count (persistent! @x))))
    ; "Elapsed time: 582.699983 msecs"

    (time
     (let [x (atom [])]
       (dotimes [i 10000000]
         (swap! x conj i))
       (count @x)))
    ; "Elapsed time: 1014.411053 msecs"

    (time
     (let [x (volatile! [])]
       (dotimes [i 10000000]
         (vswap! x conj i))
       (count @x)))
    ; "Elapsed time: 665.942603 msecs"
    )

  ;
  )



(comment
  
  (time
   (loop [i 10000000
          x (transient {})]
     (when (> i 0)
       
       (recur (dec i) (-> x
                          (assoc! :a 1)
                          (assoc! :b 2)
                          (assoc! :c 3))))))
  ; "Elapsed time: 577.725074 msecs"
  
  (time
   (loop [i 10000000
          x {}]
     (when (> i 0)
       (recur (dec i) (merge x {:a 1
                                :b 2
                                :c 3}) ))))
  ; "Elapsed time: 4727.433252 msecs"
  
  
  (time
   (loop [i 10000000
          x (transient {})]
     (when (> i 0)

       (recur (dec i) (-> (transient (persistent! x))
                          (assoc! :a 1)
                          (assoc! :b 2)
                          (assoc! :c 3))))))
  ; "Elapsed time: 2309.336101 msecs"
  
  
  ;
  )