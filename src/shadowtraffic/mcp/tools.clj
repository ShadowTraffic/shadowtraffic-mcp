(ns shadowtraffic.mcp.tools
  "The ShadowTraffic tools, independent of the MCP transport.
  Each tool fn takes a map of string keys to argument values and either
  returns a string or throws an ex-info whose message is reported as a tool error."
  (:require [clojure.string :as str]
            [shadowtraffic.mcp.docker :as docker]))

(def default-run-timeout-seconds 30)
(def bootstrap-timeout-seconds 120)

(defn- fail! [msg]
  (throw (ex-info msg {::tool-error true})))

(defn bootstrap-flag [kind]
  (case (some-> kind str str/trim str/lower-case)
    "avro" "--bootstrap-from-avro-schema"
    ("json schema" "json-schema" "jsonschema" "json") "--bootstrap-from-json-schema"
    (fail! (str "Unsupported schema kind " (pr-str kind)
                ". Expected \"Avro\" or \"JSON Schema\"."))))

(defn- required-string [args k]
  (let [v (get args k)]
    (if (and (string? v) (not (str/blank? v)))
      v
      (fail! (str "Missing required string argument: " k)))))

(defn bootstrap [args]
  (let [from   (bootstrap-flag (get args "kind"))
        schema (required-string args "schema")
        target (let [t (get args "target")]
                 (if (str/blank? t) "kafka" t))
        result (docker/run-container! {:contents        schema
                                       :mount-path      "/home/schema"
                                       :args            ["--action" "bootstrap"
                                                         from "/home/schema"
                                                         "--bootstrap-to" target]
                                       :timeout-seconds bootstrap-timeout-seconds})]
    (cond
      (:timed-out? result)
      (fail! (str "Bootstrap did not finish within " bootstrap-timeout-seconds " seconds.\n"
                  (docker/format-output result)))

      (not (zero? (:exit result)))
      (fail! (str "Bootstrap failed with exit code " (:exit result) ".\n"
                  (docker/format-output result)))

      :else
      (:stdout result))))

(defn run-shadowtraffic [args]
  (let [config  (required-string args "config")
        timeout (let [t (get args "timeout-seconds")]
                  (cond
                    (nil? t) default-run-timeout-seconds
                    (and (number? t) (pos? t)) (long (Math/ceil (double t)))
                    :else (fail! "timeout-seconds must be a positive number.")))
        result  (docker/run-container! {:contents        config
                                        :mount-path      "/home/config.json"
                                        :args            ["--config" "/home/config.json"]
                                        :timeout-seconds timeout})]
    (cond
      (:timed-out? result)
      (str "ShadowTraffic was still running after " timeout
           " seconds and was stopped. This is expected for configs without a "
           "maxEvents or maxMs limit. Output up to that point:\n\n"
           (docker/format-output result))

      (not (zero? (:exit result)))
      (fail! (str "ShadowTraffic exited with code " (:exit result) ".\n"
                  (docker/format-output result)))

      :else
      (str "ShadowTraffic finished successfully.\n\n" (docker/format-output result)))))

(def tools
  "Tool definitions: name, description, JSON input schema, and handler fn."
  [{:name        "bootstrap"
    :description "Converts an Avro or JSON Schema into a ShadowTraffic configuration."
    :schema      {"type"       "object"
                  "properties" {"kind"   {"type"        "string"
                                          "enum"        ["Avro" "JSON Schema"]
                                          "description" "The kind of schema."}
                                "schema" {"type"        "string"
                                          "description" "The schema as a string."}
                                "target" {"type"        "string"
                                          "description" "The connection type to generate the config for, e.g. kafka, postgres, s3. Defaults to kafka."}}
                  "required"   ["kind" "schema"]}
    :handler     bootstrap}
   {:name        "run-shadowtraffic"
    :description (str "Runs ShadowTraffic for the given configuration. ShadowTraffic runs indefinitely "
                      "unless the config sets limits such as maxEvents, so the run is stopped after "
                      "timeout-seconds and the output so far is returned.")
    :schema      {"type"       "object"
                  "properties" {"config"          {"type"        "string"
                                                   "description" "The ShadowTraffic config as JSON."}
                                "timeout-seconds" {"type"        "number"
                                                   "description" (str "Seconds to let ShadowTraffic run before stopping it. Defaults to "
                                                                      default-run-timeout-seconds ".")}}
                  "required"   ["config"]}
    :handler     run-shadowtraffic}])
