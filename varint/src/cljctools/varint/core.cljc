(ns cljctools.varint.core
  (:require
   [cljctools.bytes.protocols :as bytes.protocols]
   [cljctools.bytes.core :as bytes.core]))

(do (set! *warn-on-reflection* true) (set! *unchecked-math* true))

(defn varint-size
  [x]
  (loop [i (int 0)
         x (long x)]
    (if (zero? x)
      i
      (recur (inc i) (unsigned-bit-shift-right x 7)))))

(defn encode-varint
  [x baos]
  (loop [x (long x)]
    (if (zero? (bit-and x (bit-not 0x7f)))
      (do
        (bytes.protocols/write* baos x))
      (do
        (bytes.protocols/write* baos (-> (bytes.core/unchecked-int x) (bit-and 0x7f) (bit-or 0x80)))
        (recur (unsigned-bit-shift-right x 7))))))

(defn decode-varint
  [buffer offset]
  (loop [x (long 0)
         offset (int offset)
         byte (int (bytes.core/get-byte buffer offset))
         shift (int 0)]
    (if (zero? (bit-and byte 0x80))
      (bit-or x (long (bit-shift-left (bit-and byte 0x7f) shift)))
      (recur (bit-or x (long (bit-shift-left (bit-and byte 0x7f) shift)))
             (inc offset)
             (int (bytes.core/get-byte buffer (inc offset)))
             (+ shift 7)))))

(defn encode-uint64
  [x baos]
  (encode-varint x baos))

(defn decode-uint64
  [buffer offset]
  (decode-varint buffer offset))

(defn encode-int64
  [x baos]
  (encode-varint x baos))

(defn decode-int64
  [buffer offset]
  (decode-varint buffer offset))

(defn encode-uint32
  [x baos]
  (encode-varint (long x) baos))

(defn decode-uint32
  [buffer offset]
  (decode-varint buffer offset))

(defn encode-int32
  [x baos]
  (encode-varint (long x) baos))

(defn decode-int32
  [buffer offset]
  (decode-varint buffer offset))

(defn encode-zig-zag64
  [x]
  (bit-xor (bit-shift-left x 1) (bit-shift-right x 63)))

(defn decode-zig-zag64
  [x]
  (bit-xor (unsigned-bit-shift-right x 1) (- (bit-and x 1))))

(defn encode-zig-zag32
  [x]
  (bit-xor (bit-shift-left x 1) (bit-shift-right x 31)))

(defn decode-zig-zag32
  [x]
  (decode-zig-zag64 x))

(defn encode-sint64
  [x baos]
  (encode-varint (encode-zig-zag64 x) baos))

(defn decode-sint64
  [buffer offset]
  (decode-zig-zag64 (decode-varint buffer offset)))

(defn encode-sint32
  [x baos]
  (encode-varint (encode-zig-zag32 x) baos))

(defn decode-sint32
  [buffer offset]
  (decode-zig-zag32 (decode-varint buffer offset)))



(comment

  (require
   '[cljctools.bytes.protocols :as bytes.protocols]
   '[cljctools.bytes.core :as bytes.core]
   '[cljctools.varint.core :as varint.core]
   :reload)

  [(let [baos (bytes.core/byte-array-output-stream)]
     (varint.core/encode-varint 1000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.core/buffer-wrap)) 0))

   (let [baos (bytes.core/byte-array-output-stream)]
     (varint.core/encode-varint 10000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.core/buffer-wrap)) 0))
   (let [baos (bytes.core/byte-array-output-stream)]
     (varint.core/encode-varint -1000000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.core/buffer-wrap)) 0))
   (let [baos (bytes.core/byte-array-output-stream)]
     (varint.core/encode-varint 100000000 baos)
     (varint.core/decode-varint (-> baos (bytes.protocols/to-byte-array*) (bytes.core/buffer-wrap)) 0))]


  [(let [baos (bytes.core/byte-array-output-stream)]
     (varint.core/encode-sint64 -10000 baos)
     (varint.core/decode-sint64 (-> baos (bytes.protocols/to-byte-array*) (bytes.core/buffer-wrap)) 0))]



  ;
  )



(comment

  (do
    (defn foo1
      [x]
      (bit-xor x x))

    (time
     (dotimes [i 10000000]
       (foo1 i)))
    ; "Elapsed time: 158.5587 msecs"


    (defn foo2
      [^long x]
      (bit-xor x x))

    (time
     (dotimes [i 10000000]
       (foo2 i)))
    ; "Elapsed time: 10.15018 msecs"


    (defn foo3
      [x]
      (bit-xor (int x) (int x)))

    (time
     (dotimes [i 10000000]
       (foo3 i)))
    ; "Elapsed time: 166.91291 msecs"


    (defn foo4
      [x]
      (bit-xor ^int x ^int x))

    (time
     (dotimes [i 10000000]
       (foo4 i)))
    ; "Elapsed time: 12.550342 msecs"

    ;
    )


  ;
  )