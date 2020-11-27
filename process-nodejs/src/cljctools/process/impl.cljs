(ns cljctools.process.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix pipe
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [clojure.string :as str]
   [cljs.core.async.interop :refer-macros [<p!]]
   [goog.string.format :as format]
   [goog.string :refer [format]]
   [goog.object]
   [cljs.reader :refer [read-string]]

   [cljctools.csp.op.spec :as op.spec]
   [cljctools.cljc.core :as cljc.core]

   [cljctools.process.spec :as process.spec]
   [cljctools.process.chan :as process.chan]
   [cljctools.process.protocols :as process.protocols]))

(def fs (js/require "fs"))
(def path (js/require "path"))
(def child_process (js/require "child_process"))
(def Console (.-Console (js/require "console")))

(defn spawn
  [cmd args cp-opts & opts]
  (let [process (.spawn child_process cmd args cp-opts)
        exit| (chan 1)
        stdout| (chan (sliding-buffer 1024))
        stderr| (chan (sliding-buffer 1024))
        logs (atom [])
        {:keys [::process.spec/color
                ::process.spec/process-name
                ::process.spec/print-to-stdout?] :or {color "black"
                                                      print-to-stdout? false
                                                      process-name ""}} opts]
    (when process.stdout
      (.on process.stdout "data" (fn [buffer]
                                   #_(println "buffer")
                                   #_(doseq [line (str/split-lines (.toString buffer))]
                                       (js/console.log
                                        (colors.green
                                         (format "%s: %s"
                                                 process-name
                                                 (.toString buffer)))))
                                   (let [text (.toString buffer)]
                                     (when print-to-stdout?
                                       (js/console.log text))
                                     (swap! logs conj text)
                                     (put! stdout| text)))))
    (when process.stderr
      (.on process.stderr "data" (fn [buffer]
                                   (let [text (.toString buffer)]
                                     (when print-to-stdout?
                                       (js/console.log text))
                                     (swap! logs conj text)
                                     (put! stderr| text)))))
    (.on process "close" (fn [code signal]
                           (js/console.log
                            (format "process exited with code %s, signal %s"
                                    code signal))
                           (put! exit| {::process.spec/code code
                                        ::process.spec/signal signal})
                           (close! exit|)
                           #_(println (format "process exited with code %s" code))))
    (with-meta
      {::process.spec/process process
       ::process.chan/exit| exit|
       ::process.chan/stdout| stdout|
       ::process.chan/stderr| stderr|}
      {`process.protocols/-kill (fn
                                  ([_]
                                   (process.protocols/-kill _ "SIGINT"))
                                  ([_ signal]
                                   (.kill process signal)
                                   exit|))
       `process.protocols/-kill-group (fn
                                        ([_]
                                         (process.protocols/-kill-group _ "SIGINT"))
                                        ([_ signal]
                                         (js/global.process.kill (- (.-pid process)) signal)
                                         exit|))
       `process.protocols/-print-logs (fn
                                        ([_]
                                         (process.protocols/-print-logs _ nil))
                                        ([_ {:keys [::process.spec/n]}]
                                         (str/join "\n" (if n (take-last n @logs)
                                                            @logs))))})))

(defn kill
  ([process]
   (process.protocols/-kill process))
  ([process signal]
   (process.protocols/-kill process signal)))

(defn kill-group
  ([process]
   (process.protocols/-kill-group process))
  ([process signal]
   (process.protocols/-kill-group process signal)))


(comment

  (spawn "ls" #js [] (clj->js {"stdio" #_["pipe"] ["pipe"
                                                   js/global.process.stdout
                                                   js/global.process.stderr]
                               "detached" true}))

  (.spawn child_process "ls -a"  (clj->js {"stdio" ["pipe"
                                                    js/global.process.stdout
                                                    js/global.process.stderr]
                                           "detached" false
                                           "shell" "/bin/bash"}))

  (go
    (let [p (spawn "ls" #js [] (clj->js {"stdio" ["pipe"]
                                         "detached" true})
                   ::process.spec/process-name "ls")]
      (<! (::process.chan/exit| p))
      (println "output:")
      (close! (::process.chan/stdout| p))
      (println (<! (a/into [] (::process.chan/stdout| p))))))

  (def p (spawn "tail -f /dev/null" #js [] (clj->js {"stdio" ["pipe"]
                                                     "shell" "/bin/bash"
                                                     "detached" true})
                ::process.spec/process-name "/dev/null"))
  (process.protocols/-kill p)
  (kill p)

  (def p (spawn "sh f dev"
                #js [] (clj->js {"stdio" ["pipe"]
                                 "shell" "/bin/bash"
                                 "env" (js/Object.assign
                                        #js {}
                                        js/global.process.env
                                        #js {"SHADOWCLJS_NREPL_PORT" 8895
                                             "SHADOWCLJS_HTTP_PORT" 9635
                                             "SHADOWCLJS_DEVTOOLS_URL" "http://localhost:9635"
                                             "SHADOWCLJS_DEVTOOLS_HTTP_PORT" 9555})
                                 "cwd" "/ctx/DeathStarGame/bin/scenario"
                                 "detached" true})))

  (.-pid (::process.spec/process p))
  (js/global.process.kill (- (.-pid (::process.spec/process p))) "SIGINT")
  (kill-group p)

  (js/global.process.kill 994 "SIGINT")

  (go
    (<! (kill-group p))
    (println "process exited"))

  (println js/global.process.pid)
  (println js/global.process.pid)

  (println (js-keys js/global.process.env))
  (aget js/global.process.env "PWD")

  ;;
  )

(comment

  (def console (Console. (clj->js {"stdout" js/global.process.stdout
                                   "stderr" js/global.process.stderr})))

  (.log console "3")

  ;;
  )

