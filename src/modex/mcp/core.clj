(ns modex.mcp.core
  (:require [modex.mcp.protocols :as mcp]
            [modex.mcp.server :as server]
            [modex.mcp.tools :as tools]
            [clojure.core :as cc]
            [clojure.repl] ; just for pst stack trace.
            [taoensso.timbre :as log]
            [nrepl.server :as nrepl]
            [clojure.java.shell :refer [sh]])
  (:import [java.io File])
  (:gen-class))

; Optional nREPL for live dev:
(def nrepl-bind-address (System/getenv "NREPL_BIND_ADDRESS")) ; won't start if nil. usually 127.0.0.1
(def nrepl-port (or (some-> (System/getenv "NREPL_PORT") (parse-long)) 4001))

(server/redirect-logs-to-stderr!) ; any logs or prn's to stdio will break MCP over stdio transport.

(def !server-ready? (atom false))

(defn to-raw-temp-file [raw-contents]
  (let [config-file-base (str (random-uuid))
        config-file-extension ".txt"
        config-file-name (str config-file-base config-file-extension)
        config-file (File/createTempFile config-file-base config-file-extension)]
    (spit config-file raw-contents)
    (.getAbsolutePath config-file)))

(def my-tools
  "Define your tools here."
  (tools/tools
    (bootstrap
     "Converts an Avro or JSON Schema into a ShadowTraffic configuration."
     [^{:type :string :doc "The kind of schema - Avro or JSON Schema."} kind
      ^{:type :string :doc "The schema as a string."} schema]
     [(let [from (if (= kind "JSON Schema")
                   "--bootstrap-from-json-schema"
                   "--bootstrap-from-avro-schema")
            tmp-file (to-raw-temp-file schema)]
        (sh "docker" "run"
            "--rm" "-i" "--network" "host"
            "-v" (str tmp-file ":/home/schema")
            "-e" "LICENSE_ID"
            "-e" "LICENSE_EMAIL"
            "-e" "LICENSE_ORGANIZATION"
            "-e" "LICENSE_EDITION"
            "-e" "LICENSE_EXPIRATION"
            "-e" "LICENSE_SIGNATURE"
            "shadowtraffic/shadowtraffic:latest"
            "--action" "bootstrap"
            from "/home/schema"
            "--bootstrap-to" "kafka"))])

    (run-shadowtraffic
     "Runs ShadowTraffic for the given configuration."
     [^{:type :string :doc "The ShadowTraffic config"} config]
     [(let [tmp-file (to-raw-temp-file config)]
        (sh "docker" "run"
            "--rm" "-i" "--network" "host"
            "-v" (str tmp-file ":/home/config.json")
            "-e" "LICENSE_ID"
            "-e" "LICENSE_EMAIL"
            "-e" "LICENSE_ORGANIZATION"
            "-e" "LICENSE_EDITION"
            "-e" "LICENSE_EXPIRATION"
            "-e" "LICENSE_SIGNATURE"
            "shadowtraffic/shadowtraffic:latest"
            "--config" "/home/config.json"))])))

(defn init-server
  "Blocking init function for long-running I/O like connecting to remote databases.
  Modex will notify the MCP client when ready.

  Here we simulate a DB connect call that takes 1 second.

  With no delay, notification/initialized can be sent before the init request, but it still works. Will fix."
  [init-params]
  ;(Thread/sleep 1000)
  (reset! !server-ready? true))

(def my-mcp-server
  "Here we create a reified instance of AServer. Only tools are presently supported."
  (server/->server
    {:name       "Modex MCP Server"
     :version    "0.0.1"
     :initialize init-server
     :tools      my-tools
     :prompts    nil
     :resources  nil}))

(defn -main
  "Starts an MCP server that talks JSON-RPC over stdio/stdout."
  [& args]
  (log/debug "Starting nREPL...")
  (let [nrepl-server (if (and (string? nrepl-bind-address) (int? nrepl-port)) ; todo move nrepl config to args or env.
                       (nrepl/start-server :bind nrepl-bind-address :port nrepl-port)
                       nil)]
    (log/debug "Server starting via -main")
    (try
      (server/start-server! my-mcp-server) ; todo: impl. shutdown signal (return fn?)
      (catch Throwable t
        (log/error "Fatal error in -main:" (.getMessage t))
        (log/error "Stack trace:" (with-out-str (clojure.repl/pst)))
        (.printStackTrace t (java.io.PrintWriter. *err*)))
      (finally
        (when nrepl-server
          (nrepl/stop-server nrepl-server))))))

(comment

  (require '[clojure.repl])
  (throw (ex-info "hi" {}))
  (with-out-str (clojure.repl/pst))

  (-main)

  "You can init server (this will call init-server):"
  (mcp/initialize my-mcp-server)

  "You can list tools:"
  (mcp/list-tools my-mcp-server)

  "You can invoke a tool with:"                             ; will probably rename to invoke.
  (mcp/call-tool my-mcp-server :greet {:first-name "Petrus"}))
