;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns containium.core
  (:require [containium.systems :refer (with-systems)]
            [containium.systems.cassandra :as cassandra]
            [containium.systems.elasticsearch :as elastic]
            [containium.systems.kafka :as kafka]
            [containium.systems.http-kit :as http-kit]
            [containium.systems.ring-session-cassandra :as cass-session]
            [containium.systems.fs-deploy :as fs]
            [containium.modules :as modules]
            [clojure.edn :as edn]
            [clojure.java.io :refer (resource)]
            [clojure.string :refer (split trim)]
            [boxure.core :refer (boxure) :as boxure]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.pprint :refer (pprint)])
  (:import [jline.console ConsoleReader]
           [java.util Timer TimerTask]))


;;; Globals for REPL access. A necessary evil.

(defonce globals (atom {}))


;;; Box logic.

(defn check-project
  [project]
  (->> (list (when-not (:containium project)
               "Missing :containium configuration in project.clj.")
             (when-not (-> project :containium :start)
               "Missing :start configuration in :containium of project.clj.")
             (when-not (-> project :containium :stop)
               "Missing :stop configuration in :containium of project.clj.")
             (when (-> project :containium :ring)
               (when-not (-> project :containium :ring :handler)
                 "Missing :handler configuration in :ring of :containium of project.clj.")))
       (remove nil?)))


;;--- FIXME: Check for (and allow somehow?) for duplicates.

(defn start-box
  "The logic for starting a box."
  [module resolve-dependencies root-config systems isolate]
  (println "Starting module" module "...")
  (try
    (let [project (boxure/jar-project module)]
      (if-let [errors (seq (check-project project))]
        (apply println "Could not start module" (:name project) "for the following reasons:\n  "
               (interpose "\n  " errors))
        (let [box (boxure {:resolve-dependencies resolve-dependencies
                           :isolate isolate}
                          (.getClassLoader clojure.lang.RT)
                          module)
              module-config (:containium project)
              start-fn @(boxure/eval box `(do (require '~(symbol (namespace (:start module-config))))
                                              ~(:start module-config)))]
          (if (instance? Throwable start-fn)
            (do (boxure/clean-and-stop box)
                (throw start-fn))
            (try
              (let [start-result (boxure/call-in-box box start-fn root-config systems)]
                (when (:ring module-config) (http-kit/upstart-box box))
                (println "Module" (:name box) "started.")
                (assoc box :start-result start-result))
              (catch Throwable ex
                (boxure/clean-and-stop box)
                (throw ex)))))))
    (catch Throwable ex
      (println "Exception while starting module" module ":" ex)
      (.printStackTrace ex))))


(defn start-boxes
  "Starts all the boxes in the specified spec."
  [spec systems isolate]
  (let [{:keys [config modules resolve-dependencies]} spec]
    (loop [modules modules
           boxes {}]
      (if-let [module (first modules)]
        (if-let [result (start-box module resolve-dependencies config systems isolate)]
          (recur (rest modules) (assoc boxes (:name result) result))
          (recur (rest modules) boxes))
        boxes))))


(defn stop-box
  [box]
  (let [name (:name box)
        module-config (-> box :project :containium)]
    (println "Stopping module" name "...")
    (try
      (when (:ring module-config) (http-kit/remove-box box))
      (let [stop-fn @(boxure/eval box
                                  `(do (require '~(symbol (namespace (:stop module-config))))
                                       ~(:stop module-config)))]
        (boxure/call-in-box box stop-fn (:start-result box)))
      (catch Throwable ex
        (println "Exception while stopping module" name ":" ex)
        (.printStackTrace ex))
      (finally
        (boxure/clean-and-stop box)
        (println "Module" name "stopped.")))))


(defn stop-boxes
  "Calls the stop function of all boxes in the specefied boxes map."
  [boxes]
  (doseq [box (vals boxes)]
    (stop-box box)))


;;; Command loop.

(defmulti handle-command
  "This multi-method dispatches on the command argument. It also
  receives the command arguments, the spec, the current command-loop
  state and the running boxes map. This method may return a pair,
  where the first item is an updated state map, and the second item is
  an updated boxes map. If no value is returned, or a value in the
  pair is nil, then the state and/or boxes are not updated in the
  command-loop."
  (fn [command args spec systems isolate state boxes] command))


(defmethod handle-command :default
  [command _ _ _ _ _]
  (println "Unknown command:" command)
  (println "Type 'help' for info on the available commands."))


(defmethod handle-command "help"
  [_ _ _ _ _ _ _]
  (println (str "Available commands are:"
                "\n"
                "\n module <list|start|stop> [path|name]"
                "\n   Prints a list of running modules."
                "\n"
                "\n pprint <spec|systems|isolate|state|boxes>"
                "\n   Pretty prints the specified variable for debugging."
                "\n"
                "\n repl <start|stop> [port]"
                "\n   Starts an nREPL at the specified port, or stops the current one, inside"
                "\n   the containium."
                "\n"
                "\n shutdown"
                "\n   Stops all boxes and systems gracefully."
                "\n"
                "\n threads"
                "\n   Prints a list of all threads.")))


(defmethod handle-command "repl"
  [_ args spec _ _ state _]
  (let [[action port-str] args]
    (case action
      "stop" (if-let [server (:nrepl state)]
               (do (nrepl/stop-server server)
                   (println "nREPL server stopped.")
                   [(dissoc state :nrepl)])
               (println "No active nREPL server to stop."))
      "start" (if-let [server (:nrepl state)]
                (println "An nREPL server is already running, on port" (:port server))
                (if port-str
                  (if-let [port (try (Integer/parseInt port-str) (catch Exception ex))]
                    (let [server (nrepl/start-server :port port)]
                      (println "nREPL server started on port" port-str)
                      [(assoc state :nrepl server)])
                    (println "Invalid port number:" port-str))
                  (let [server (nrepl/start-server)]
                    (println "nREPL server started on port" (:port server))
                    [(assoc state :nrepl server)])))
      (println (str "Unknown action '" action "', please use 'start' or 'stop'.")))))


(defmethod handle-command "threads"
  [_ _ _ _ _ _ _]
  (let [threads (keys (Thread/getAllStackTraces))]
    (println (apply str "Current threads (" (count threads) "):\n  "
                    (interpose "\n  " threads)))))


(defmethod handle-command "module"
  [_ args spec systems isolate _ boxes]
  (let [[action arg] args]
    (case action
      "list" (println (apply str "Current modules (" (count boxes) "):\n  "
                             (interpose "\n  " (keys boxes))))
      "start" (let [{:keys [config resolve-dependencies]} spec]
                (if arg
                  (when-let [box (start-box arg resolve-dependencies config systems isolate)]
                    [nil (assoc boxes (:name box) box)])
                  (println "Missing path argument.")))
      "stop" (if arg
               (if-let [box (boxes arg)]
                 (do (stop-box box)
                     [nil (dissoc boxes arg)])
                 (println "Unknown module:" arg))
               (println "Missing name argument."))
      (println (str "Unknown action '" action "', please use 'list', 'start' or 'stop'.")))))


(defmethod handle-command "pprint"
  [_ args spec systems isolate state boxes]
  (let [[var] args]
    (pprint (case var
              "spec" spec
              "systems" systems
              "isolate" isolate
              ;; ---TODO: Fix printing of nREPL server.
              "state" state
              "boxes" boxes
              (println (str "Unknown variable '" var
                            "', please use 'spec', 'systems', 'isolate', 'state' or 'boxes'"))))))


(defn shutdown-state
  "Go over the current command-loop state, and shutdown anything that
  needs shutting down when the containium is about to stop."
  [state]
  (when-let [server (:nrepl state)]
    (nrepl/stop-server server)
    (println "nREPL server stopped.")))


(defn handle-commands
  "This functions starts the command loop. It uses the handle-command
  multi-method for handling the individual commands (except shutdown).
  See the documentation on the handle-command for more info on this.
  This function receives the spec, the root systems and a map of
  started boxes. More boxes may be started from the command loop, or
  stopped. Therefore, this function returns an updated map of
  currently running boxes."
  [spec systems boxes isolate]
  ;; Handle commands like starting and stopping modules, and stopping the application.
  ;; This can be done through typing here, updates on the file system, through sockets...
  (let [jline (ConsoleReader.)]
    (loop [state {}
           boxes boxes]
      (swap! globals assoc :boxes boxes)
      (let [[command & args] (split (trim (.readLine jline "containium> ")) #"\s+")]
        (case command
          "" (recur state boxes)
          "shutdown" (do (shutdown-state state) boxes)
          (let [[new-state new-boxes]
                (try
                  (handle-command command args spec systems isolate state boxes)
                  (catch Throwable t
                    (println "Error handling command" command ":")
                    (.printStackTrace t)))]
            (recur (or new-state state) (or new-boxes boxes))))))))


;;; Thread debug on shutdown.

(defn shutdown-timer
  "Start a timer that shows debug information, iff the JVM has not
  shutdown yet and `wait` seconds have passed."
  [wait]
  (let [timer (Timer. "shutdown-timer" true)
        task (proxy [TimerTask] []
               (run []
                 (let [threads (keys (Thread/getAllStackTraces))]
                   (println (apply str "Threads still running (" (count threads) "):\n  "
                                   (interpose "\n  " threads))))))]
    (.schedule timer task (* wait 1000))))


;;; The coordinating functions.

(defn run
  "This function is used for the with-systems function. It is called
  when all root systems are up and running. Currently it starts the
  boxes, enters the command loop, and stops the boxes when the command
  loop exited."
  [spec systems isolates]
  (swap! globals assoc :systems systems)
  (let [isolate (apply str (interpose "|" (remove nil? isolates)))
        boxes (start-boxes spec systems isolate)
        boxes (handle-commands spec systems boxes isolate)]
    (stop-boxes boxes)))


(defn -main
  [& args]
  (let [spec (-> "spec.clj" resource slurp edn/read-string)
        ]
    (swap! globals assoc :spec spec)
    (with-systems [;; [:cassandra cassandra/system]
                   ;; [:elastic elastic/system]
                   ;; [:kafka kafka/system]
                   ;; [:http-kit http-kit/system]
                   ;; [:session-store cass-session/system]
                   [:fs fs/system]]
      (:config spec)
      {}
      ["containium.*"]
      (partial run spec)))
  (shutdown-agents)
  (shutdown-timer 10))
