(ns cljctools.edit.core
  (:require
   [clojure.string]
   [clojure.pprint :refer [pprint]]
   #?(:cljs [cljs.reader :refer [read-string]])
   #?(:cljs [goog.string.format])
   #?(:cljs [goog.string :refer [format]])
   [clojure.spec.alpha :as s]

   [rewrite-clj.zip :as z]
   [rewrite-clj.parser :as parser]
   [rewrite-clj.parser.core :as parser.core]
   [rewrite-clj.node :as n]
   [rewrite-clj.node.forms :as nforms]
   [rewrite-clj.zip.base :as zip.base]
   [rewrite-clj.reader :as reader]
   [rewrite-clj.paredit]
   [rewrite-clj.node.protocols :as node]

   [clojure.tools.reader.reader-types :as r]

   [cljctools.edit.spec :as edit.spec])
  #?(:cljs
     (:import [goog.string StringBuffer])))


(defn read-ns-symbol
  "Reads the namespace name (ns foo.bar ,,,) from a string.
   String after ns form can be invalid. 
   File may start with comments, drop-reads one form at a time until finds ns"
  [string]
  (let [reader (reader/string-reader string)
        nodes (->> (repeatedly #(parser/parse reader))
                   (sequence
                    (comp
                     (drop-while (complement
                                  (fn [node]
                                    (= :seq (node/node-type node)))))))
                   (take 1))
        node (with-meta
               (nforms/forms-node nodes)
               (meta (first nodes)))
        zloc (zip.base/edn node {:track-position? true})  #_(z/of-string (n/string node))
        zloc-ns (-> zloc
                    z/down
                    (z/find-next
                     (fn [zloc-current]
                       (let [node-type (node/node-type (-> zloc-current z/node))]
                         (or
                          (= node-type :symbol)
                          (= node-type :meta))))))
        ns-symbol (->
                   (if (= :meta (node/node-type (-> zloc-ns z/node)))
                     (->
                      (sequence
                       (comp
                        (take-while identity)
                        (take-while (complement z/end?)))
                       (iterate  z/next zloc-ns))
                      last)
                     zloc-ns)
                   z/sexpr)]
    ns-symbol))

 (defn ^{:deprecated "0.0.0"} position-at
   "[deprecated] we never need offset"
   [string offset]
   (let [reader (reader/string-reader string)]
     (loop []
       (let [c (r/read-char reader)
             line (r/get-line-number reader)
             column (r/get-column-number reader)
             current-offset #?(:clj (.. reader -rdr -rdr -rdr -rdr -s-pos)
                               :cljs (.. reader -rdr -rdr -s-pos))]
         (cond
           (= offset current-offset)
           [line column]

           (nil? c)
           (reader/throw-reader reader "Unexpected EOF.")

           :else (recur))))))


(defn ^{:deprecated "0.0.0"} offset-at
  "[deprecated] we never need offset"
  [string [row col :as position]]
  (let [reader (reader/string-reader string)
        string-buffer (StringBuffer.)]
    (loop []
      (let [c (r/read-char reader)
            line (r/get-line-number reader)
            column (r/get-column-number reader)
            current-offset #?(:clj (.. reader -rdr -rdr -rdr -rdr -s-pos) ; does not work on jvm
                              :cljs (.. reader -rdr -rdr -s-pos))]
        (cond
          (= [line column] [row col])
          current-offset

          (nil? c)
          (reader/throw-reader reader "Unexpected EOF.")

          :else (recur))))))

(defn split-at-position
  "note: normalizes new lines"
  [string [row col :as position]]
  (let [lines (clojure.string/split string #"\r?\n" -1)
        string-left (as-> lines x
                      (take (dec row) x)
                      (vec x)
                      (conj x (->
                               (get lines (dec row))
                               (subs 0 (dec col))))
                      (clojure.string/join "\n" x)
                      #_(clojure.string/reverse))
        string-right (as-> lines x
                       (drop row x)
                       (conj x (->
                                (get lines row)
                                (subs (dec col))))
                       (clojure.string/join "\n" x))]
    [string-left string-right])
  #_(let [offset (offset-at string [6755 19] #_[29 31])
          string-left (subs string 0 offset)
          string-right (subs string offset)]
      (println offset)
      (println (subs string-left (- (count string-left) 100))))
  #_(let [reader (reader/string-reader string)
          left-string-buffer (StringBuffer.)
          right-string-buffer (StringBuffer.)]
      (loop []
        (let [c (r/read-char reader)
              line (r/get-line-number reader)
              column (r/get-column-number reader)]
          (cond
            (nil? c)
            (do nil)

            (or
             (< line row)
             (and (= line row) (<= column col)))
            (do
              (.append  left-string-buffer c)
              (recur))

            (or
             (and (= line row) (> column col))
             (> line row))
            (do
              (.append  right-string-buffer c)
              (recur)))))
      [left-string-buffer right-string-buffer]))

#_(fn [c]
    (cond (nil? c)               :eof
          (reader/whitespace? c) :whitespace
          (= c *delimiter*)      :delimiter
          :else (get {\^ :meta      \# :sharp
                      \( :list      \[ :vector    \{ :map
                      \} :unmatched \] :unmatched \) :unmatched
                      \~ :unquote   \' :quote     \` :syntax-quote
                      \; :comment   \@ :deref     \" :string
                      \: :keyword}
                     c :token)))

(defn scan
  "A process that scans string in both direction of position
   Scan understands from where and to where the expresion(s) is to then parse it with rewrite-clj
   Returns start and end position of a string to pass to rewrite-clj parse-string-all"
  [string position]
  (let [[row col] position
        [string-left string-right] (split-at-position string position)
        string-left-reversed (clojure.string/reverse string-left)
        reader-left (reader/string-reader string-left-reversed)
        reader-right (reader/string-reader string-right)
        stateV (volatile! {:left-target-position nil
                           :right-target-position nil})]
    (loop []
      (cond
        
        
        )

      (let [char-left (r/read-char reader-left)
            char-right (r/read-char reader-right)]))))

(s/def ::expand-level #{:nearest-element
                        :all-elements
                        :whole-collection})

(defn parse-forms-at-position
  "Returns a lazy sequence of forms at position. Every next element returns next form expansion.
   On every read from sequence, the string is read just enough to return the next form until the top level form.
   Given e.g. form and position ({:a [:b 1 | ]}), lazy seq will give elements 1 , [:b 1] , {:a [:b 1]} , ({:a [:b 1 |]})
   If we're in the middle of a collection, should be able to specify in options: select nearest left/right element, select all elements, select whole collection form
   "
  [string [row col :as position] 
   {:keys [::expand-level]
    :or {expand-level :nearest-element} :as opts}]
  (let [[string-left string-right] (split-at-position string [29 31])
        string-left-reversed (clojure.string/reverse string-left)]
    (println string-left-reversed)
    #_(println (subs string-left (- (count string-left) 100)))))