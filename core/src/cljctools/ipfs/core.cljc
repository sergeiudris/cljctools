(ns cljctools.ipfs.core
  (:require
   [clojure.string]
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.runtime.core :as bytes.runtime.core]
   [cljctools.varint.core :as varint.core]
   [cljctools.ipfs.crypto :as ipfs.crypto]
   [cljctools.ipfs.runtime.crypto :as ipfs.runtime.crypto]
   [cljctools.ipfs.runtime.core :as ipfs.runtime.core]

   [cljctools.ipfs.protocols :as ipfs.protocols]
   [cljctools.ipfs.spec :as ipfs.spec]))

(defn multiaddress-to-data
  [multiaddress]
  {})

(defn decode-mplex
  ([buffer]
   (decode-mplex buffer 0))
  ([buffer offset]
   (let [header (varint.core/decode-varint buffer 0)
         flag (bit-and header 0x07)
         stream-id (bit-shift-right header 3)
         header-size (varint.core/varint-size header)
         msg-length (varint.core/decode-varint buffer header-size)
         msg-length-size (varint.core/varint-size msg-length)]
     {:flag (case flag
              0 :new-stream
              1 :message-receiver
              2 :message-initiator
              3 :close-receiver
              4 :close-initiator
              5 :reset-receiver
              6 :reset-initiator)
      :stream-id stream-id
      :msg-length msg-length
      :msgBB (bytes.runtime.core/buffer-wrap buffer (+ header-size msg-length-size) msg-length)})))

(defn encode-mplex
  [{:as data
    :keys [flag stream-id msgBB]}]
  (bytes.runtime.core/concat
   [(let [baos (bytes.runtime.core/byte-array-output-stream)]
      (varint.core/encode-varint (bit-or (bit-shift-left stream-id 3) flag) baos)
      (varint.core/encode-varint (bytes.runtime.core/capacity msgBB) baos)
      (-> baos (bytes.protocols/to-byte-array*) (bytes.runtime.core/buffer-wrap)))
    msgBB]))







