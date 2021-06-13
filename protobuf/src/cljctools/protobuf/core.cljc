(ns cljctools.protobuf.core
  "should be clojure data -> bytes out -> data, no .proto files, compilers and other crap - just data -> bytes -> data -> bytes"
  (:require
   [clojure.spec.alpha :as s]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.spec :as bytes.spec]
   [cljctools.bytes.core :as bytes.core]
   [cljctools.varint.core :as varint.core]))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn encode-fixed
  [x baos n]
  (dotimes [i (int n)]
    (bytes.protocols/write* baos (-> (bit-shift-right x (* i 8)) (bytes.core/unchecked-int) (bit-and 0xff)))))

(defn encode-little-endian32
  [x baos]
  (encode-fixed x baos 4))

(defn encode-little-endian64
  [x baos]
  (encode-fixed x baos 8))

(def default-registry
  {})

(def wire-types
  (->>
   {0 #{::int32 ::int64 ::uint32 ::uint64 ::sint32 ::sint64 ::boolean ::enum}
    1 #{::fixed64 ::sfixed64 ::double}
    2 #{::string ::byte-array ::map ::sequential}
    3 #{:deprecated}
    4 #{:deprecated}
    5 #{::fixed32 ::sfixed32 ::float}}
   (map (fn [[wire-type wire-type-set]]
          (map (fn [value-type] [value-type wire-type])  wire-type-set)))
   (flatten)
   (apply hash-map)))

(defmulti encode*
  (fn
    ([value value-type registry baos]
     (cond
       (bytes.core/byte-array? value) ::byte-array
       (string? value) ::string
       (map? value) ::map
       (keyword? value) ::enum
       :else value-type))
    ([value value-type registry baos dispatch-val]
     dispatch-val)))

(defmethod encode* ::byte-array
  [value value-type registry baos & more]
  (let [byte-arr-length (bytes.core/alength value)]
    (varint.core/encode-uint32 byte-arr-length baos)
    (bytes.protocols/write-byte-array* baos value)))

(defmethod encode* ::string
  [value value-type registry baos & more]
  (encode* (bytes.core/to-byte-array value) value-type registry baos ::byte-array))

(defmethod encode* ::int32
  [value value-type registry baos & more]
  (varint.core/encode-int32 value baos))

(defmethod encode* ::int64
  [value value-type registry baos & more]
  (varint.core/encode-int64 value baos))

(defmethod encode* ::uint32
  [value value-type registry baos & more]
  (varint.core/encode-uint32 value baos))

(defmethod encode* ::uint64
  [value value-type registry baos & more]
  (varint.core/encode-uint64 value baos))

(defmethod encode* ::sint32
  [value value-type registry baos & more]
  (varint.core/encode-sint32 value baos))

(defmethod encode* ::sint64
  [value value-type registry baos & more]
  (varint.core/encode-sint64 value baos))

(defmethod encode* ::boolean
  [value value-type registry baos & more]
  (bytes.protocols/write* baos (if value 1 0)))

(defmethod encode* ::fixed32
  [value value-type registry baos & more]
  (encode-fixed value baos 4))

(defmethod encode* ::fixed64
  [value value-type registry baos & more]
  (encode-fixed value baos 8))

(defmethod encode* ::sfixed32
  [value value-type registry baos & more]
  (encode-fixed value baos 4))

(defmethod encode* ::sfixed64
  [value value-type registry baos & more]
  (encode-fixed value baos 8))

(defmethod encode* ::float
  [value value-type registry baos & more]
  (encode-fixed (bytes.core/float-to-raw-int-bits value) baos 4))

(defmethod encode* ::double
  [value value-type registry baos & more]
  (encode-fixed (bytes.core/double-to-raw-long-bits value) baos 8))

(defn encode-tag
  [field-number wire-type baos]
  (->
   (-> (bit-shift-left field-number 3) (bit-or wire-type))
   (varint.core/encode-uint32 baos)))

(defmethod encode* ::enum
  [value value-type registry baos & more]
  (varint.core/encode-int32 (get-in registry [value-type value]) baos))

(defmethod encode* ::map
  [value value-type registry baos & more]
  (let [value-proto (get registry value-type)]
    (doseq [[k k-value] value
            :let [{k-value-type :value-type
                   k-field-number :field-number} (get value-proto k)
                  k-wire-type (get wire-types k-value-type 2)]]
      (cond
        (sequential? k-value)
        (doseq [seq-value k-value]
          (encode-tag k-field-number k-wire-type baos)
          (encode* seq-value k-value-type registry baos))

        (map? k-value)
        (do
          (encode-tag k-field-number k-wire-type baos)
          (let [baos-map (bytes.core/byte-array-output-stream)]
            (encode* k-value k-value-type registry baos-map)
            (encode* (bytes.protocols/to-byte-array* baos-map) ::byte-array registry baos ::byte-array)))

        :else
        (do
          (encode-tag k-field-number k-wire-type baos)
          (encode* k-value k-value-type registry baos))))))

(defn encode
  [value value-type registry]
  (let [baos (bytes.core/byte-array-output-stream)]
    (encode* value value-type registry baos)
    (bytes.protocols/to-byte-array* baos)))

(defmulti decode*
  (fn
    ([buffer value-type registry]
     value-type)
    ([buffer value-type registry dispatch-val]
     dispatch-val)))

(defmethod decode* ::byte-array
  [buffer value-type registry & more]
  (bytes.core/to-byte-array buffer))

(defmethod decode* ::string
  [buffer value-type registry & more]
  (bytes.core/to-string buffer))

(defmethod decode* ::int32
  [buffer value-type registry & more]
  (varint.core/decode-int32 buffer))

(defmethod decode* ::int64
  [buffer value-type registry & more]
  (varint.core/decode-int64 buffer))

(defmethod decode* :default
  [buffer value-type registry & more]
  (let [value-proto (get registry value-type)]))

(defn decode
  [buffer value-type registry]
  (decode* buffer value-type registry))


(comment

  (require
   '[cljctools.bytes.spec :as bytes.spec]
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.varint.core :as varint.core]
   '[cljctools.protobuf.core :as protobuf.core]
   :reload)


  (def registry
    {::Record {:key {:field-number 1
                     :value-type ::byte-array}
               :value {:field-number 2
                       :value-type ::byte-array}
               :author {:field-number 3
                        :value-type ::byte-array}
               :signature {:field-number 4
                           :value-type ::byte-array}
               :timeReceived {:field-number 5
                              :value-type ::string}}

     ::MessageType {:PUT_VALUE 0
                    :GET_VALUE 1
                    :ADD_PROVIDER 2
                    :GET_PROVIDERS 3
                    :FIND_NODE 4
                    :PING 5}

     ::ConnectionType {:NOT_CONNECTED 0
                       :CONNECTED 1
                       :CAN_CONNECT 2
                       :CANNOT_CONNECT 3}

     ::Peer {:id {:field-number 1
                  :value-type ::byte-array}
             :addrs {:field-number 2
                     :value-type ::byte-array
                     :repeated? true}
             :connection {:field-number 3
                          :value-type ::ConnectionType
                          :enum? true}}

     ::Message {:type {:field-number 1
                       :value-type ::MessageType
                       :enum? true}
                :key {:field-number 2
                      :value-type ::byte-array}
                :record {:field-number 3
                         :value-type ::Record}
                :closerPeers {:field-number 8
                              :value-type ::Peer
                              :repeated? true}
                :providerPeers {:field-number 8
                                :value-type ::Peer
                                :repeated? true}
                :clusterLevelRaw {:field-number 10
                                  :value-type ::int32}}})

  (let [registry (merge default-registry
                        registry)
        msg
        {:type :PUT_VALUE
         :key (bytes.core/byte-array 5)
         :record {:key (bytes.core/byte-array 5)
                  :value (bytes.core/byte-array 5)
                  :author (bytes.core/byte-array 5)
                  :signature (bytes.core/byte-array 5)
                  :timeReceived "1970-01-01"}
         :closerPeers [{:id (bytes.core/byte-array 5)
                        :addrs [(bytes.core/byte-array 5)
                                (bytes.core/byte-array 5)]
                        :connection :CONNECTED}
                       {:id (bytes.core/byte-array 5)
                        :addrs [(bytes.core/byte-array 5)
                                (bytes.core/byte-array 5)]
                        :connection :CONNECTED}]
         :providerPeers [{:id (bytes.core/byte-array 5)
                          :addrs [(bytes.core/byte-array 5)
                                  (bytes.core/byte-array 5)]
                          :connection :CONNECTED}
                         {:id (bytes.core/byte-array 5)
                          :addrs [(bytes.core/byte-array 5)
                                  (bytes.core/byte-array 5)]
                          :connection :CONNECTED}]
         :clusterLevelRaw 123}]
    (->
     (encode msg ::Message registry)))




  ;
  )