(ns shadowtraffic.mcp.tools-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [shadowtraffic.mcp.docker :as docker]
            [shadowtraffic.mcp.server :as server]
            [shadowtraffic.mcp.tools :as tools])
  (:import [io.modelcontextprotocol.spec McpSchema$TextContent]
           [java.io File]))

(defn- result-text [result]
  (.text ^McpSchema$TextContent (first (.content result))))

(deftest bootstrap-flag-test
  (is (= "--bootstrap-from-avro-schema" (tools/bootstrap-flag "Avro")))
  (is (= "--bootstrap-from-avro-schema" (tools/bootstrap-flag " avro ")))
  (is (= "--bootstrap-from-json-schema" (tools/bootstrap-flag "JSON Schema")))
  (is (= "--bootstrap-from-json-schema" (tools/bootstrap-flag "json")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported schema kind \"protobuf\""
                        (tools/bootstrap-flag "protobuf"))))

(deftest docker-command-test
  (let [cmd (docker/docker-command {:container-name "c" :input-path "/tmp/in"
                                    :mount-path "/home/config.json" :args ["--config" "/home/config.json"]})]
    (is (= ["docker" "run" "--rm" "--network" "host" "--name" "c" "-v" "/tmp/in:/home/config.json"]
           (take 9 cmd)))
    (is (= ["shadowtraffic/shadowtraffic:latest" "--config" "/home/config.json"] (take-last 3 cmd)))
    (doseq [v docker/license-env-vars]
      (is (some #{v} cmd)))))

(deftest read-tail-test
  (let [f (File/createTempFile "read-tail" ".txt")]
    (try
      (spit f "\u001B[32m✔\u001B[0m ok")
      (is (= "✔ ok" (docker/read-tail f 100)))
      (spit f (apply str (repeat 100 "x")))
      (is (= (str "...[earlier output truncated]...\n" (apply str (repeat 10 "x")))
             (docker/read-tail f 10)))
      (finally (.delete f)))))

(deftest bootstrap-test
  (testing "passes the right arguments and returns stdout"
    (let [!call (atom nil)]
      (with-redefs [docker/run-container! (fn [opts] (reset! !call opts) {:exit 0 :stdout "{\"generators\":[]}" :stderr "noise"})]
        (is (= "{\"generators\":[]}" (tools/bootstrap {"kind" "JSON Schema" "schema" "{}"})))
        (is (= ["--action" "bootstrap" "--bootstrap-from-json-schema" "/home/schema" "--bootstrap-to" "kafka"]
               (:args @!call)))
        (tools/bootstrap {"kind" "Avro" "schema" "{}" "target" "postgres"})
        (is (= "postgres" (last (:args @!call)))))))
  (testing "non-zero exit is an error"
    (with-redefs [docker/run-container! (constantly {:exit 1 :stdout "" :stderr "bad schema"})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?s)exit code 1.*bad schema"
                            (tools/bootstrap {"kind" "Avro" "schema" "{}"})))))
  (testing "missing schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema"
                          (tools/bootstrap {"kind" "Avro"})))))

(deftest run-shadowtraffic-test
  (testing "timeout defaults to 30 and is reported as a normal result"
    (let [!call (atom nil)]
      (with-redefs [docker/run-container! (fn [opts] (reset! !call opts) {:timed-out? true :stdout "event" :stderr ""})]
        (let [out (tools/run-shadowtraffic {"config" "{}"})]
          (is (= 30 (:timeout-seconds @!call)))
          (is (str/includes? out "still running after 30 seconds"))
          (is (str/includes? out "event"))))))
  (testing "custom timeout, including fractional numbers"
    (let [!call (atom nil)]
      (with-redefs [docker/run-container! (fn [opts] (reset! !call opts) {:exit 0 :stdout "done" :stderr ""})]
        (is (str/includes? (tools/run-shadowtraffic {"config" "{}" "timeout-seconds" 2.5}) "finished successfully"))
        (is (= 3 (:timeout-seconds @!call))))))
  (testing "invalid timeout"
    (is (thrown? clojure.lang.ExceptionInfo (tools/run-shadowtraffic {"config" "{}" "timeout-seconds" -1})))
    (is (thrown? clojure.lang.ExceptionInfo (tools/run-shadowtraffic {"config" "{}" "timeout-seconds" "10"}))))
  (testing "non-zero exit is an error"
    (with-redefs [docker/run-container! (constantly {:exit 1 :stdout "required key [generators] not found" :stderr ""})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?s)code 1.*generators"
                            (tools/run-shadowtraffic {"config" "{}"}))))))

(deftest call-tool-test
  (let [ok  (server/call-tool (fn [args] (str "hi " (get args "name"))) {"name" "there"})
        err (server/call-tool (fn [_] (throw (ex-info "nope" {::tools/tool-error true}))) {})]
    (is (false? (.isError ok)))
    (is (= "hi there" (result-text ok)))
    (is (true? (.isError err)))
    (is (= "nope" (result-text err)))))

(deftest tool-specs-test
  (let [specs (mapv server/tool-spec tools/tools)]
    (is (= ["bootstrap" "run-shadowtraffic"] (mapv #(.name (.tool %)) specs)))
    (is (= ["kind" "schema"] (vec (get (.inputSchema (.tool (first specs))) "required"))))))

(defn- docker-available? []
  (try (zero? (:exit (sh "docker" "info"))) (catch Exception _ false)))

(deftest ^:docker run-container-test
  (if-not (docker-available?)
    (println "Skipping run-container-test: docker is not available.")
    (binding [docker/*image* "alpine:3"]
      (let [tmp     (io/file (System/getProperty "java.io.tmpdir"))
            n-temps #(count (filter (fn [^File f] (str/starts-with? (.getName f) "shadowtraffic-mcp-"))
                                    (.listFiles tmp)))
            before  (n-temps)]
        (testing "captures stdout, stderr and the exit code"
          (let [r (docker/run-container! {:contents "hello" :mount-path "/home/in"
                                          :args ["sh" "-c" "cat /home/in; echo oops >&2; exit 3"]
                                          :timeout-seconds 60})]
            (is (= 3 (:exit r)))
            (is (= "hello" (:stdout r)))
            (is (= "oops\n" (:stderr r)))))
        (testing "stops containers that outlive the timeout"
          (let [r (docker/run-container! {:contents "" :mount-path "/home/in"
                                          :args ["sh" "-c" "echo started; sleep 300"]
                                          :timeout-seconds 3})]
            (is (:timed-out? r))
            (is (= "started\n" (:stdout r)))
            (is (str/blank? (:out (sh "docker" "ps" "-q" "--filter" "name=shadowtraffic-mcp-"))))))
        (testing "temp files are deleted"
          (is (= before (n-temps))))))))
