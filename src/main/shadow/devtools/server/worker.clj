(ns shadow.devtools.server.worker
  (:refer-clojure :exclude (compile))
  (:require [clojure.core.async :as async :refer (go thread alt!! alt! <!! <! >! >!!)]
            [aleph.http :as aleph]
            [shadow.devtools.server.util :as util]
            [shadow.devtools.server.fs-watch :as fs-watch]
            [shadow.devtools.server.worker.impl :as impl]
            [shadow.devtools.server.worker.ws :as ws]
            [aleph.netty :as netty])
  (:import (java.util UUID)))

(defn configure
  "re-configure the build"
  [proc {:keys [id] :as config}]
  {:pre [(map? config)
         (keyword? id)]}
  (impl/configure proc config))

(defn compile
  "triggers an async compilation, use watch to receive notification about worker state"
  [proc]
  (impl/compile proc))

(defn compile!
  "triggers an async compilation and waits for the compilation result (blocking)"
  [proc]
  (impl/compile! proc))

(defn watch
  "watch all output produced by the worker"
  ([proc chan]
   (watch proc chan true))
  ([proc chan close?]
   (impl/watch proc chan close?)))

(defn start-autobuild
  "automatically compile on file changes"
  [proc]
  (impl/start-autobuild proc))

(defn stop-autobuild [proc]
  (impl/stop-autobuild proc))

(defn sync!
  "ensures that all proc-control commands issued have completed"
  [proc]
  (let [chan (async/chan)]
    (>!! (:proc-control proc) {:type :sync! :chan chan})
    (<!! chan))
  proc)

(defn repl-eval-connect
  "called by processes that are able to eval repl commands and report their result

   client-out should be a channel that receives things generated by shadow.cljs.repl
   (:repl/invoke, :repl/require, etc)

   returns a channel the results of eval should be put in
   when no more results are coming this channel should be closed"
  [proc client-id client-out]
  (impl/repl-eval-connect proc client-id client-out))

(defn repl-client-connect
  "connects to a running worker as a repl client who can send things to eval and receive their result

   client-in should receive strings which represent cljs code
   will remove the client when client-in closes
   returns a channel that will receive results from client-in
   the returned channel is closed if the worker is stopped"
  [proc client-id client-in]
  (impl/repl-client-connect proc client-id client-in))

;; SERVICE API

(defn start [fs-watch]
  (let [proc-id
        (UUID/randomUUID) ;; FIXME: not really unique but unique enough

        ;; closed when the proc-stops
        ;; nothing will ever be written here
        ;; its for linking other processes to the server process
        ;; so they shut down when the worker stops
        proc-stop
        (async/chan)

        ;; controls the worker, registers new clients, etc
        proc-control
        (async/chan)

        ;; we put output here
        output
        (async/chan)

        ;; clients tap here to receive output
        output-mult
        (async/mult output)

        fs-updates
        (async/chan)

        http-config-ref
        (volatile! nil) ;; {:port 123 :localhost foo}

        channels
        {:proc-stop proc-stop
         :proc-control proc-control
         :output output
         :fs-updates fs-updates}

        thread-state
        {::impl/worker-state true
         :http-config-ref http-config-ref
         :proc-id proc-id
         :eval-clients {}
         :repl-clients {}
         :pending-results {}
         :channels channels
         :compiler-state nil}

        state-ref
        (volatile! thread-state)

        thread-ref
        (util/server-thread
          state-ref
          thread-state
          {proc-stop nil
           proc-control impl/do-proc-control
           fs-updates impl/do-fs-updates}
          {:do-shutdown
           (fn [state]
             (>!! output {:type :worker-shutdown})
             state)})

        worker-proc
        {::impl/proc true
         :proc-stop proc-stop
         :proc-id proc-id
         :proc-control proc-control
         :fs-updates fs-updates
         :output output
         :output-mult output-mult
         :thread-ref thread-ref
         :state-ref state-ref}]

    (fs-watch/subscribe fs-watch fs-updates)

    ;; ensure all channels are cleanup up properly
    (go (<! thread-ref)
        (async/close! output)
        (async/close! proc-stop)
        (async/close! fs-updates))

    (let [http-config
          {:port 0
           :host "localhost"}

          http
          (aleph/start-server
            (fn [ring]
              (ws/process (assoc worker-proc :http-config @http-config-ref) ring))
            http-config)

          http-config
          (assoc http-config
            :port (netty/port http))]

      (vreset! http-config-ref http-config)
      (assoc worker-proc
        :http-config http-config
        :http http))))

(defn stop [{:keys [http] :as proc}]
  {:pre [(impl/proc? proc)]}
  (.close http)
  (async/close! (:proc-stop proc))
  (<!! (:thread-ref proc)))
