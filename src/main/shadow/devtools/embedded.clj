(ns shadow.devtools.embedded
  (:require [shadow.server.runtime :as rt]
            [shadow.devtools.server.worker :as worker]
            [shadow.devtools.server.supervisor :as super]
            [shadow.devtools.server.config :as config]
            [shadow.devtools.server.util :as util]
            [shadow.devtools.server.common :as common]
            [clojure.core.async :as async :refer (go <!)]
            [shadow.devtools.api :as api]))

(def default-config
  {:verbose false})

(defonce system-ref
  (volatile! nil))

(defn system []
  (let [x @system-ref]
    (when-not x
      (throw (ex-info "devtools not started" {})))
    x))

(defn app []
  (merge
    (common/app)
    {:supervisor
     {:depends-on [:fs-watch]
      :start super/start
      :stop super/stop}

     :out
     {:depends-on [:config]
      :start (fn [{:keys [verbose]}]
               (util/stdout-dump verbose))
      :stop async/close!}
     }))

(defn start!
  ([]
   (start! default-config))
  ([config]
   (if @system-ref
     ::running
     (let [system
           (-> {::started (System/currentTimeMillis)
                :config config}
               (rt/init (app))
               (rt/start-all))]

       (vreset! system-ref system)
       ::started))))

(defn stop! []
  (when-some [system @system-ref]
    (rt/stop-all system)
    (vreset! system-ref nil))
  ::stopped)

(defn start-worker
  ([build-id]
   (start-worker build-id true))
  ([build-id autobuild]
   (start!)
   (let [build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))

         {:keys [supervisor out] :as app}
         (system)]

     (if-let [worker (super/get-worker supervisor build-id)]
       (when autobuild
         (worker/start-autobuild worker))

       (-> (super/start-worker supervisor build-id)
           (worker/watch out false)
           (worker/configure build-config)
           (cond->
             autobuild
             (worker/start-autobuild))
           (worker/sync!))
       ))
   ::started))

(defn stop-worker [build-id]
  (when-let [{:keys [supervisor] :as sys} @system-ref]
    (super/stop-worker supervisor build-id))
  ::stopped)

(defn stop-autobuild [build-id]
  (let [{:keys [supervisor] :as sys} @system-ref]
    (if-not sys
      ::not-running
      (let [worker (super/get-worker supervisor build-id)]
        (if-not worker
          ::no-worker
          (do (worker/stop-autobuild worker)
              ::stopped))))))

;; FIXME: re-use running app instead of it starting a new one
(defn node-repl
  ([]
    (api/node-repl))
  ([opts]
    (api/node-repl opts)))