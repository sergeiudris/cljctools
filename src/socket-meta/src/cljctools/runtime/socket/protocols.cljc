(ns cljctools.runtime.socket.protocols)

(defprotocol Socket
  (close* [_])
  (connect* [_])
  (send* [_ data address])
  #_IDeref)