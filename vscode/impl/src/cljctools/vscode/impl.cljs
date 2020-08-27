(ns cljctools.vscode.impl
  (:require
   [clojure.core.async :as a :refer [chan go go-loop <! >!  take! put! offer! poll! alt! alts! close!
                                     pub sub unsub mult tap untap mix admix unmix
                                     timeout to-chan  sliding-buffer dropping-buffer
                                     pipeline pipeline-async]]
   [goog.string :refer [format]]
   [goog.object]
   [clojure.string :as string]
   [cljs.reader :refer [read-string]]
   #_["fs" :as fs]
   #_["path" :as path]
   [cognitect.transit :as transit]
   [cljs.nodejs :as node]

   #_[cljctools.paredit :as paredit]
   [cljctools.csp.op.spec :as op.spec]
   [cljctools.vscode.spec :as vscode.spec]
   [cljctools.vscode.chan :as vscode.chan]
   [cljctools.vscode.protocols :as vscode.p]))

(def fs (node/require "fs"))
(def path (node/require "path"))

(def ^:const NS_DECL_LINE_RANGE 100)

(declare vscode)
(try
  (def vscode (node/require "vscode"))
  (catch js/Error e (do (println "Error on (node/require vscode)") (println e))))

(comment

  (exists? js/global)
  (type js/global)
  js/global.process.versions

  (def worker-threads (node/require "worker_threads"))
  (type worker-threads.isMainThread)
   

  ;;
  )


(defn show-information-message*
  [vscode msg]
  (.. vscode.window (showInformationMessage msg)))

(defn register-command*
  [{:keys [::vscode.spec/cmd-id
           ::vscode
           ::context
           ::on-cmd] :as ops}]
  (let [disposable (.. vscode.commands
                       (registerCommand
                        cmd-id
                        (fn [& args]
                          (on-cmd cmd-id #_args))))]
    (.. context.subscriptions (push disposable))))

(defn register-commands*
  [{:keys [::vscode.spec/cmd-ids
           ::vscode
           ::context
           ::on-cmd] :as ops}]
  (let []
    (doseq [cmd-id cmd-ids]
      (register-command* {::vscode.spec/cmd-id cmd-id
                          ::vscode vscode
                          ::context context
                          ::on-cmd on-cmd}))))

(defn read-workspace-file
  [filepath callback]
  (let []
    (as-> nil o
      (.join path vscode.workspace.rootPath filepath)
      (vscode.Uri.file o)
      (.readFile vscode.workspace.fs o)
      (.then o callback))))

; https://stackoverflow.com/a/41029103/10589291

(defn js-lookup
  [obj]
  (-> (fn [result key]
        (let [v (goog.object/get obj key)]
          (if (= "function" (goog/typeOf v))
            result
            (assoc result key v))))
      (reduce {} (.getKeys goog/object obj))))

(defn js-lookup-nested
  [obj]
  (if (goog.isObject obj)
    (-> (fn [result key]
          (let [v (goog.object/get obj key)]
            (if (= "function" (goog/typeOf v))
              result
              (assoc result key (js-lookup-nested v)))))
        (reduce {} (.getKeys goog/object obj)))
    obj))

(comment

  vscode.workspace.rootPath

  vscode.workspace.workspaceFile
  vscode.workspace.workspaceFolders

  (as-> nil o
    (.join path vscode.workspace.rootPath ".vscode")
    (vscode.Uri.file o)
    (.readDirectory vscode.workspace.fs o)
    (.then o (fn [d] (println d))))

  (as-> nil o
    (.join path vscode.workspace.rootPath ".vscode/mult.edn")
    (vscode.Uri.file o)
    (.readFile vscode.workspace.fs o)
    (.then o (fn [d] (println d))))

  ; https://code.visualstudio.com/api/references/vscode-api#TextDocument
  ; https://code.visualstudio.com/api/references/vscode-api#Selection
  ; https://code.visualstudio.com/api/references/vscode-api#Range

  (if  vscode.window.activeTextEditor
    vscode.window.activeTextEditor.document.uri
    :no-active-editor)

  (println vscode.window.activeTextEditor.selection)

  (js-lookup-nested vscode.window.activeTextEditor.selection)

  (do
    (def start vscode.window.activeTextEditor.selection.start)
    (def end vscode.window.activeTextEditor.selection.end)
    (def range (vscode.Range. start end))
    (def text (.getText vscode.window.activeTextEditor.document range)))

  ;;
  )

(defn parse-ns
  "Safely tries to read the first form from the source text.
   Returns ns name or nil"
  [filepath text log]
  (try
    (when (re-matches #".+\.clj(s|c)?" filepath)
      (let [fform (read-string text)]
        (when (= (first fform) 'ns)
          (second fform))))
    (catch js/Error error (log "; parse-ns error " {::vscode.spec/filepath filepath
                                                    ::error error}))))

(defn readdir
  "https://nodejs.org/api/fs.html#fs_fs_readdir_path_options_callback"
  ([dirpath]
   (readdir dirpath (chan 1)))
  ([dirpath out|]
   (.readdir fs dirpath #js {}
             (fn [err filenames]
               (put! out| (or err filenames))))
   out|))

(defn readfile
  "https://nodejs.org/api/fs.html#fs_fs_readfile_path_options_callback"
  ([filepath]
   (readfile filepath (chan 1)))
  ([filepath out|]
   (.readFile fs filepath
             (fn [err data]
               (put! out| (or err data))))
   out|))

(defn active-ns
  [active-text-editor log]
  (when active-text-editor
    (let [range (vscode.Range.
                 (vscode.Position. 0 0)
                 (vscode.Position. NS_DECL_LINE_RANGE 0))
          text (.getText active-text-editor.document range)
          active-document-filepath active-text-editor.document.fileName
          ns-symbol (parse-ns active-document-filepath text log)
          data {::vscode.spec/filepath active-document-filepath
                ::vscode.spec/ns-symbol ns-symbol}]
      data
      #_(prn active-text-editor.document.languageId))))

#_(defn tabapp-html
    [{:keys [vscode context panel script-path html-path script-replace] :as opts}]
    (def panel panel)
    (let [script-uri (as-> nil o
                       (.join path context.extensionPath script-path)
                       (vscode.Uri.file o)
                       (.asWebviewUri panel.webview o)
                       (.toString o))
          html (as-> nil o
                 (.join path context.extensionPath html-path)
                 (.readFileSync fs o)
                 (.toString o)
                 (string/replace o script-replace script-uri))]
      html))

#_(defn make-panel
    [vscode context id handlers]
    (let [panel (vscode.window.createWebviewPanel
                 id
                 "mult tab"
                 vscode.ViewColumn.Two
                 #js {:enableScripts true
                      :retainContextWhenHidden true})]
      (.onDidDispose panel (fn []
                             ((:on-dispose handlers) id)))
      (.onDidReceiveMessage  panel.webview (fn [msg]
                                             (let [data (read-string msg)]
                                               ((:on-message handlers) id data))))
      (.onDidChangeViewState panel (fn [panel]
                                     ((:on-state-change handlers) {:active? panel.active})))

      panel))

(defn create-tab*
  [{:as channels
    :keys [::vscode.chan/tab-recv|
           ::vscode.chan/tab-evt|]}
   {:as opts
    :keys [::context
           ::vscode.spec/tab-id
           ::vscode.spec/tab-title
           ::vscode.spec/tab-view-column
           ::vscode.spec/tab-script-filepath
           ::vscode.spec/tab-html-filepath
           ::vscode.spec/tab-script-replace]
    :or {tab-id (random-uuid)
         tab-title "Default title"
         tab-script-filepath "resources/out/tabapp.js"
         tab-html-filepath "resources/index.html"
         tab-script-replace "/out/tabapp.js"
         tab-view-column vscode.ViewColumn.Two}}]
  (let [{:keys [on-message on-dispose on-state-change]
         :or {on-message (fn [msg]
                           (let [value (read-string msg)]
                             (vscode.chan/op
                              {::op.spec/op-key ::vscode.chan/tab-recv}
                              (::vscode.chan/tab-recv| channels)
                              value tab-id)))
              on-dispose (fn []
                           (vscode.chan/op
                            {::op.spec/op-key ::vscode.chan/tab-disposed}
                            (::vscode.chan/tab-evt| channels)
                            tab-id))
              on-state-change (fn [data] (do nil))}} opts
        panel (vscode.window.createWebviewPanel
               tab-id
               tab-title
               tab-view-column
               #js {:enableScripts true
                    :retainContextWhenHidden true})
        _ (do (.onDidDispose panel (fn []
                                     (on-dispose)))
              (.onDidReceiveMessage  panel.webview (fn [msg]
                                                     (on-message msg)))
              (.onDidChangeViewState panel (fn [panel]
                                             (on-state-change {::vscode.spec/tab-active? panel.active}))))
        script-uri (as-> nil o
                     (.join path context.extensionPath tab-script-filepath)
                     (vscode.Uri.file o)
                     (.asWebviewUri panel.webview o)
                     (.toString o))
        html (as-> nil o
               (.join path context.extensionPath tab-html-filepath)
               (.readFileSync fs o)
               (.toString o)
               (string/replace o tab-script-replace script-uri))
        lookup opts]
    (set! panel.webview.html html)
    (reify
      vscode.p/Send
      (-send [_ v] (.postMessage (.-webview panel) (pr-str v)))
      vscode.p/Release
      (-release [_] (println "release for tab not implemented"))
      vscode.p/Active
      (-active? [_] panel.active)
      cljs.core/ILookup
      (-lookup [_ k] (-lookup _ k nil))
      (-lookup [_ k not-found] (-lookup lookup k not-found)))))



; repl only
(def ^:dynamic *context* (atom nil))

(defn create-proc-ops
  [channels opts]
  (let [{:keys [::vscode.chan/ops|m
                ::vscode.chan/evt|
                ::vscode.chan/tab-evt|m
                ::vscode.chan/tab-send|m]} channels
        ops|t (tap ops|m (chan 10))
        tab-evt|t (tap tab-evt|m (chan (sliding-buffer 10)))
        tab-send|t (tap tab-send|m (chan 10))
        release #(do
                   (untap ops|m  ops|t)
                   (close! ops|t))
        state (->
               {::vscode.spec/tabs {}}
               (merge (select-keys opts [::context]))
               (atom))]
    (do
      (.onDidChangeActiveTextEditor vscode.window (fn [text-editor]
                                                    (let [data (active-ns text-editor prn)]
                                                      (when data
                                                        (prn ".onDidChangeActiveTextEditor")
                                                        #_(put! ops| (p/-vl-texteditor-changed ops|i data)))))))
    (go
      (loop []
        (when-let [[v port] (alts! [ops|t tab-evt|t tab-send|t])]
          (condp = port
            ops|t
            (condp = (select-keys v [::op.spec/op-key ::op.spec/op-type])

              {::op.spec/op-key ::vscode.chan/extension-activate}
              (let [{:keys [::context]} v]
                (println ::extension-activate)
                (reset! *context* context)
                (swap! state assoc ::context context)
                (put! evt| (dissoc v ::context)))

              {::op.spec/op-key ::vscode.chan/register-commands}
              (let [{:keys [::vscode.spec/cmd-ids]} v
                    on-cmd (fn [cmd-id #_args]
                             (prn ::cmd cmd-id)
                             (vscode.chan/op
                              {::op.spec/op-key ::vscode.chan/cmd}
                              (::vscode.chan/cmd| channels)
                              cmd-id))]
                (register-commands* {::vscode.spec/cmd-ids cmd-ids
                                     ::vscode vscode
                                     ::context (::context @state)
                                     ::on-cmd on-cmd}))

              {::op.spec/op-key ::vscode.chan/show-info-msg}
              (let [{:keys [::vscode.spec/info-msg]} v]
                (show-information-message* vscode info-msg))

              {::op.spec/op-key ::vscode.chan/tab-create}
              (let [{:keys [::vscode.spec/tab-id]} v
                    tab (create-tab* channels (merge (select-keys @state [::context]) v))]
                (swap! state update ::vscode.spec/tabs assoc tab-id tab))


              {::op.spec/op-key ::vscode.chan/read-dir
               ::op.spec/op-type ::op.spec/request}
              (let [{:keys [::vscode.spec/dirpath out|]} v]
                (take! (readdir dirpath) (fn [filenames]
                                           (vscode.chan/op
                                            {::op.spec/op-key ::vscode.chan/read-dir
                                             ::op.spec/op-type ::op.spec/response}
                                            out| filenames)))))
            tab-evt|t
            (condp = (:op v)
              {::op.spec/op-key ::vscode.chan/tab-disposed}
              (let [{:keys [::vscode.spec/tab-id]} v]
                (swap! state update ::vscode.spec/tabs dissoc tab-id)))

            tab-send|t
            (let [{:keys [::vscode.spec/tab-id]} v
                  tab (get-in @state [::vscode.spec/tabs tab-id])]
              (vscode.p/-send tab v)))
          (recur)))
      (println "; proc-host go-block exits"))
    (reify
      vscode.p/Release
      (-release [_] (release))
      #_p/Host
      #_(-show-info-msg [_ msg] (show-information-message* vscode msg))
      #_(-register-commands [_ opts])
      #_(-create-tab [_ opts]
                     (create-tab* (merge {:context (:context @state)} opts)))
      #_(-read-workspace-file [_ filepath]
        (let [c| (chan 1)]
          (read-workspace-file filepath (fn [file] (put! c| (.toString file)) (close! c|)))
          c|))
      #_(-join-workspace-path [_ subpath]
        (let [extpath (. (::context @state) -extensionPath)]
          (.join path extpath subpath)))
      vscode.p/Editor
      (-active-ns [_] (active-ns vscode.window.activeTextEditor prn))
      (-selection [_]
        (when  vscode.window.activeTextEditor
          (let [start vscode.window.activeTextEditor.selection.start
                end vscode.window.activeTextEditor.selection.end
                range (vscode.Range. start end)
                text (.getText vscode.window.activeTextEditor.document range)]
            text)))
      ILookup
      (-lookup [_ k] (-lookup _ k nil))
      (-lookup [_ k not-found] (-lookup @state k not-found)))))


;; (defn activate
;;   [{:keys [::spec/ops|] :as channels} context]
;;   (reset! *context* context)
;;   (prn "vscode.api makef-activate")
;;   (put! ops| {:op ::spec/extension-activate :context context}))

;; (defn deactivate
;;   [{:keys [::spec/ops|] :as channels}]
;;   (js/console.log "cljctools.vscode.api/makef-deactivate"))

;; (defn show-info-msg
;;   [host msg]
;;   (p/-show-info-msg host msg))

;; (defn register-commands
;;   [host opts]
;;   (p/-register-commands host opts))

;; (defn create-tab
;;   [host opts]
;;   (p/-create-tab host opts))

;; (defn send-tab
;;   [tab v]
;;   (p/-send tab v))

(comment

  (read-string (str (ex-info "err" {:a 1})))


  (defn roundtrip [x]
    (let [w (t/writer :json)
          r (t/reader :json)]
      (t/read r (t/write w x))))

  (defn test-roundtrip []
    (let [list1 [:red :green :blue]
          list2 [:apple :pear :grape]
          data  {(t/integer 1) list1
                 (t/integer 2) list2}
          data' (roundtrip data)]
      (assert (= data data'))))

  ;;
  )