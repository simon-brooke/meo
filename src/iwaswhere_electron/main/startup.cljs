(ns iwaswhere-electron.main.startup
  (:require [taoensso.timbre :as timbre :refer-macros [info error]]
            [child_process :refer [spawn fork]]
            [electron :refer [app session shell]]
            [http :as http]
            [path :refer [join normalize]]
            [iwaswhere-electron.main.runtime :as rt]
            [fs :refer [existsSync renameSync readFileSync]]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

(def PORT (:port rt/runtime-info))

(defn jvm-up? [{:keys [put-fn current-state cmp-state]}]
  (info "JVM up?" (:attempt current-state))
  (let [try-again
        (fn [_]
          (info "- Nope, trying again")
          (when-not (:service @cmp-state)
            (put-fn [:cmd/schedule-new {:timeout 10 :message [:jvm/start]}]))
          (put-fn [:window/new {:url       "loading.html"
                                :width     400
                                :height    300
                                :window-id "loading"}])
          (put-fn [:cmd/schedule-new {:timeout 1000 :message [:jvm/loaded?]}]))
        res-handler
        (fn [res]
          (let [status-code (.-statusCode res)]
            (info "HTTP response: " status-code (= status-code 200))
            (if (= status-code 200)
              (do (put-fn [:window/new {:url    (:index-page rt/runtime-info)
                                        :cached true}])
                  (put-fn (with-meta [:window/close] {:window-id "loading"})))
              (try-again res))))
        req (http/get (clj->js {:host "localhost" :port PORT}) res-handler)]
    (.on req "error" try-again)
    {:new-state (update-in current-state [:attempt] #(inc (or % 0)))}))

(defn spawn-process [cmd args opts]
  (info "STARTUP: spawning" cmd args opts)
  (spawn cmd (clj->js args) (clj->js opts)))

(defn start-jvm [{:keys [current-state]}]
  (let [{:keys [user-data app-path jar blink data-path java cwd
                repo-dir]} rt/runtime-info
        args ["-Dapple.awt.UIElement=true" "-XX:+AggressiveOpts" "-jar" jar]
        opts {:detached false
              :cwd      user-data
              :env      {:PORT            PORT
                         :DATA_PATH       data-path
                         :BLINK_PATH      blink
                         :GIT_COMMITS     (not repo-dir)
                         :CACHED_APPSTATE true}}
        service (spawn-process java args opts)
        std-out (.-stdout service)
        std-err (.-stderr service)]
    (info "JVM: startup" (with-out-str (pp/pprint rt/runtime-info)))
    (.on std-out "data" #(info "JVM " (.toString % "utf8")))
    (.on std-err "data" #(error "JVM " (.toString % "utf8")))
    {:new-state (assoc-in current-state [:service] service)}))

(defn start-geocoder [_]
  (info "STARTUP: start geocoder")
  (let [{:keys [user-data app-path cwd node-path]} rt/runtime-info
        geocoder (spawn-process node-path
                                [(str app-path "/electron/geocoder.js")]
                                {:detached true
                                 :stdio    "ignore"
                                 :cwd      app-path})]
    (info "GEOCODER spawned" geocoder)
    {}))

(defn start-spotify [_]
  (info "STARTUP: start spotify")
  (let [{:keys [user-data app-path cwd node-path]} rt/runtime-info
        spotify (spawn-process node-path
                               [(str app-path "/electron/spotify.js")]
                               {:detached true
                                :stdio    "ignore"
                                :cwd      app-path
                                :env      {:USER_DATA user-data
                                           :APP_PATH  app-path}})]
    (info "SPOTIFY spawned" spotify)
    {}))

(defn shutdown [{:keys []}]
  (info "Shutting down")
  (.quit app)
  {})

(defn open-external [{:keys [msg-payload]}]
  (let [url msg-payload
        img-path (:img-path rt/runtime-info)]
    (when-not (str/includes? url (str "localhost:" (:port rt/runtime-info) "/#"))
      (info "Opening" url)
      (.openExternal shell url))
    ; not working with blank spaces, e.g. Library/Application Support/
    #_(when (str/includes? url "localhost:7788/photos")
        (let [img (str/replace url "http://localhost:7788/photos/" "")
              path (str "'" (join img-path img) "'")]
          (info "Opening item" path
                (.openItem shell path)))))
  {})

(defn shutdown-jvm [{:keys [current-state]}]
  (let [pid (readFileSync (:pid-file rt/runtime-info) "utf-8")]
    (info "Shutting down JVM service" pid)
    (when pid
      (if (= (:platform rt/runtime-info) "win32")
        (spawn-process "TaskKill" ["-F" "/PID" pid] {})
        (spawn-process "/bin/kill" ["-KILL" pid] {}))))
  {})

(defn clear-cache [{:keys []}]
  (info "Clearing Electron Cache")
  (let [session (.-defaultSession session)]
    (.clearCache session #(info "Electron Cache Cleared")))
  {})

(defn clear-iww-cache [{:keys []}]
  (info "Clearing iWasWhere Cache")
  (let [cache-file (:cache rt/runtime-info)
        cache-exists? (.existsSync fs cache-file)]
    (when cache-exists?
      (.renameSync fs cache-file (str cache-file ".bak"))))
  {})

(defn cmp-map [cmp-id]
  {:cmp-id      cmp-id
   :handler-map {:jvm/start           start-jvm
                 :jvm/loaded?         jvm-up?
                 :spotify/start       start-spotify
                 :geocoder/start      start-geocoder
                 :app/shutdown        shutdown
                 :app/open-external   open-external
                 :app/shutdown-jvm    shutdown-jvm
                 :app/clear-iww-cache clear-iww-cache
                 :app/clear-cache     clear-cache}})

(.on app "window-all-closed"
     (fn [ev]
       (info "window-all-closed")
       (when-not (= (:platform rt/runtime-info) "darwin")
         (.quit app))))