(ns cljctools.socket.protocols)

(defprotocol Socket
  (close* [_])
  (connect* [_])
  (send* [_ data])
  #_IDeref)