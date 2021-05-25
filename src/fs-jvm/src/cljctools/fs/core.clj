(ns cljctools.fs.core
  (:refer-clojure :exclude [remove])
  (:require
   [clojure.java.io :as io]
   [cljctools.fs.protocols :as fs.protocols])
  (:import (java.io Writer)))

(defn path-join
  [& args]
  (->
   (apply io/file args)
   (.getCanonicalPath)))

(defn path-exists?
  [filepath]
  (.exists (io/file filepath)))

(defn read-file
  [filepath]
  (slurp filepath))

(defn write-file
  [filepath data-string]
  (spit filepath data-string))

(defn make-parents
  [filepath]
  (io/make-parents filepath))

(deftype TWriter [^Writer io-writer]
  fs.protocols/PWriter
  (write*
    [_ string]
    (.write io-writer ^String string))
  (close*
    [_]
    (.close io-writer)))

(defn writer
  [x & opts]
  (let [io-writer (apply io/writer x opts)]
    (TWriter.
     io-writer)))

(defn remove
  ([filepath]
   (remove filepath true))
  ([filepath silently?]
   (apply io/delete-file filepath silently?)))