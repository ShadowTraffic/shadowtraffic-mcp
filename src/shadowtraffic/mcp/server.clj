(ns shadowtraffic.mcp.server
  "MCP server over stdio, built on the official MCP Java SDK."
  (:require [shadowtraffic.mcp.docker :as docker]
            [shadowtraffic.mcp.tools :as tools])
  (:import [io.modelcontextprotocol.json McpJsonDefaults]
           [io.modelcontextprotocol.server McpServer McpServerFeatures$SyncToolSpecification McpSyncServer]
           [io.modelcontextprotocol.server.transport StdioServerTransportProvider]
           [io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$CallToolResult
            McpSchema$ServerCapabilities McpSchema$Tool]
           [java.io FilterInputStream InputStream]
           [java.util.function BiFunction])
  (:gen-class))

(def server-name "shadowtraffic-mcp")
(def server-version "1.0.0")

(defn- text-result ^McpSchema$CallToolResult [^String text error?]
  (-> (McpSchema$CallToolResult/builder)
      (.addTextContent text)
      (.isError (boolean error?))
      (.build)))

(defn call-tool
  "Invokes a tool handler and converts its outcome into a CallToolResult."
  [handler args]
  (try
    (text-result (handler (into {} args)) false)
    (catch Exception e
      (when-not (::tools/tool-error (ex-data e))
        (binding [*out* *err*]
          (println "Unexpected tool exception:")
          (.printStackTrace e)))
      (text-result (or (ex-message e) (str e)) true))))

(defn tool-spec ^McpServerFeatures$SyncToolSpecification
  [{:keys [name description schema handler]}]
  (-> (McpServerFeatures$SyncToolSpecification/builder)
      (.tool (-> (McpSchema$Tool/builder)
                 (.name name)
                 (.description description)
                 (.inputSchema ^java.util.Map schema)
                 (.build)))
      (.callHandler (reify BiFunction
                      (apply [_ _exchange request]
                        (call-tool handler (or (.arguments ^McpSchema$CallToolRequest request) {})))))
      (.build)))

(defn- on-eof
  "Wraps an InputStream so that `f` is called once the stream is exhausted."
  ^InputStream [^InputStream in f]
  (let [fired (atom false)
        check (fn [n] (when (and (neg? n) (compare-and-set! fired false true)) (f)) n)]
    (proxy [FilterInputStream] [in]
      (read
        ([] (check (.read in)))
        ([b] (check (.read in ^bytes b)))
        ([b off len] (check (.read in ^bytes b (int off) (int len))))))))

(defn start!
  "Starts the server on the given streams. `on-close` is called when `in` reaches EOF."
  ^McpSyncServer [^InputStream in ^java.io.OutputStream out on-close]
  (let [transport (StdioServerTransportProvider. (McpJsonDefaults/getMapper) (on-eof in on-close) out)]
    (-> (McpServer/sync transport)
        (.serverInfo ^String server-name ^String server-version)
        (.capabilities (-> (McpSchema$ServerCapabilities/builder)
                           (.tools false)
                           (.build)))
        (.tools ^java.util.List (mapv tool-spec tools/tools))
        (.build))))

(defn -main [& _args]
  (let [closed (promise)
        server (start! System/in System/out #(deliver closed true))]
    ;; Runs on normal exit and on SIGTERM/SIGINT from the client.
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable docker/remove-running-containers!))
    ;; The transport reads stdin on its own threads. Block until the client
    ;; disconnects, then exit so the process doesn't linger.
    @closed
    (.closeGracefully server)
    (System/exit 0)))
