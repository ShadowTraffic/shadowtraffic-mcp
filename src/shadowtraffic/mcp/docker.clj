(ns shadowtraffic.mcp.docker
  "Runs the ShadowTraffic Docker image and collects its output."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.io File RandomAccessFile]
           [java.lang ProcessBuilder$Redirect]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent TimeUnit]))

(def license-env-vars
  ["LICENSE_ID" "LICENSE_EMAIL" "LICENSE_ORGANIZATION"
   "LICENSE_EDITION" "LICENSE_EXPIRATION" "LICENSE_SIGNATURE"])

(def ^:dynamic *image* "shadowtraffic/shadowtraffic:latest")

(def max-output-bytes
  "Only the tail of a container's output is returned, so long runs can't flood the client."
  20000)

(defonce ^{:doc "Names of containers started by this process that haven't exited yet."}
  !running (atom #{}))

(defn remove-container! [name]
  (sh "docker" "rm" "-f" name))

(defn remove-running-containers!
  "Force-removes every container this process started that is still running.
  Called on shutdown so abandoned runs don't keep generating data."
  []
  (run! remove-container! @!running))

(defn- temp-file ^File [prefix suffix]
  (doto (File/createTempFile prefix suffix)
    (.deleteOnExit)))

(defn strip-ansi [s]
  (str/replace s #"\u001B\[[0-9;]*m" ""))

(defn read-tail
  "Reads at most n bytes from the end of file f, without ANSI color codes."
  [^File f n]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [len   (.length raf)
          start (max 0 (- len n))
          buf   (byte-array (- len start))]
      (.seek raf start)
      (.readFully raf buf)
      (strip-ansi
        (str (when (pos? start) "...[earlier output truncated]...\n")
             (String. buf StandardCharsets/UTF_8))))))

(defn docker-command
  [{:keys [container-name input-path mount-path args]}]
  (vec (concat ["docker" "run" "--rm" "--network" "host"
                "--name" container-name
                "-v" (str input-path ":" mount-path)]
               (mapcat (fn [v] ["-e" v]) license-env-vars)
               [*image*]
               args)))

(defn run-container!
  "Runs the ShadowTraffic image with `contents` mounted at `mount-path`.
  Removes the container if it is still running after `timeout-seconds`.
  Returns {:exit ?int :timed-out? bool :stdout str :stderr str}.
  Temp files are deleted before returning."
  [{:keys [contents mount-path args timeout-seconds]}]
  (let [in-file        (temp-file "shadowtraffic-mcp-" ".txt")
        out-file       (temp-file "shadowtraffic-mcp-out-" ".log")
        err-file       (temp-file "shadowtraffic-mcp-err-" ".log")
        container-name (str "shadowtraffic-mcp-" (random-uuid))]
    (try
      (spit in-file contents)
      (let [cmd       (docker-command {:container-name container-name
                                       :input-path     (.getAbsolutePath in-file)
                                       :mount-path     mount-path
                                       :args           args})
            _         (swap! !running conj container-name)
            proc      (-> (ProcessBuilder. ^java.util.List cmd)
                          (.redirectInput (ProcessBuilder$Redirect/from (File. "/dev/null")))
                          (.redirectOutput out-file)
                          (.redirectError err-file)
                          (.start))
            finished? (.waitFor proc (long timeout-seconds) TimeUnit/SECONDS)]
        (when-not finished?
          (remove-container! container-name)
          (when-not (.waitFor proc 10 TimeUnit/SECONDS)
            (.destroyForcibly proc)))
        {:exit       (when finished? (.exitValue proc))
         :timed-out? (not finished?)
         :stdout     (read-tail out-file max-output-bytes)
         :stderr     (read-tail err-file max-output-bytes)})
      (finally
        (swap! !running disj container-name)
        (.delete in-file)
        (.delete out-file)
        (.delete err-file)))))

(defn format-output [{:keys [stdout stderr]}]
  (->> [(when-not (str/blank? stdout) (str "stdout:\n" stdout))
        (when-not (str/blank? stderr) (str "stderr:\n" stderr))]
       (remove nil?)
       (str/join "\n\n")))
