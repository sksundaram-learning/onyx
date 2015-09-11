(ns onyx.peer.monitoring-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.test-helper :refer [load-config]]
            [onyx.api]))

(def n-messages 100)

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def in-chan (chan (inc n-messages)))

(def out-chan (chan (sliding-buffer (inc n-messages))))

(defn inject-in-ch [event lifecycle]
  {:core.async/chan in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(def state (atom {}))

(defn update-state [_ event]
  (swap! state update-in [(:event event)] conj (dissoc event :event)))

(deftest monitoring-test
  (let [id (java.util.UUID/randomUUID)
        config (load-config)
        env-config (assoc (:env-config config) :onyx/id id)
        peer-config (assoc (:peer-config config) :onyx/id id)
        env (onyx.api/start-env env-config)
        peer-group (onyx.api/start-peer-group peer-config)
        batch-size 20
        catalog [{:onyx/name :in
                  :onyx/plugin :onyx.plugin.core-async/input
                  :onyx/type :input
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Reads segments from a core.async channel"}

                 {:onyx/name :inc
                  :onyx/fn :onyx.peer.monitoring-test/my-inc
                  :onyx/type :function
                  :onyx/batch-size batch-size}

                 {:onyx/name :out
                  :onyx/plugin :onyx.plugin.core-async/output
                  :onyx/type :output
                  :onyx/medium :core.async
                  :onyx/batch-size batch-size
                  :onyx/max-peers 1
                  :onyx/doc "Writes segments to a core.async channel"}]
        workflow [[:in :inc] [:inc :out]]
        lifecycles [{:lifecycle/task :in
                     :lifecycle/calls :onyx.peer.monitoring-test/in-calls}
                    {:lifecycle/task :in
                     :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.peer.monitoring-test/out-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.plugin.core-async/writer-calls}]

        _ (doseq [n (range n-messages)]
            (>!! in-chan {:n n}))

        _ (>!! in-chan :done)
        _ (close! in-chan)

        monitoring-config {:monitoring :custom
                           :zookeeper-write-log-entry update-state
                           :zookeeper-read-log-entry update-state
                           :zookeeper-write-catalog update-state
                           :zookeeper-write-workflow update-state
                           :zookeeper-write-flow-conditions update-state
                           :zookeeper-write-lifecycles update-state
                           :zookeeper-write-task update-state
                           :zookeeper-write-chunk update-state
                           :zookeeper-write-job-scheduler update-state
                           :zookeeper-write-messaging update-state
                           :zookeeper-force-write-chunk update-state
                           :zookeeper-read-catalog update-state
                           :zookeeper-read-workflow update-state
                           :zookeeper-read-flow-conditions update-state
                           :zookeeper-read-lifecycles update-state
                           :zookeeper-read-task update-state
                           :zookeeper-read-chunk update-state
                           :zookeeper-read-origin update-state
                           :zookeeper-read-job-scheduler update-state
                           :zookeeper-read-messaging update-state
                           :zookeeper-write-origin update-state
                           :zookeeper-gc-log-entry update-state}
        v-peers (onyx.api/start-peers 3 peer-group monitoring-config)
        _ (onyx.api/submit-job
            peer-config
            {:catalog catalog
             :workflow workflow
             :lifecycles lifecycles
             :task-scheduler :onyx.task-scheduler/balanced})
        results (take-segments! out-chan)]

    (let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
      (is (= (set (butlast results)) expected))
      (is (= (last results) :done)))

    (let [metrics @state]
      (is (seq? (:zookeeper-read-task metrics)))
      (is (seq? (:zookeeper-read-catalog metrics)))
      (is (seq? (:zookeeper-read-log-entry metrics)))
      (is (seq? (:zookeeper-read-workflow metrics)))
      (is (seq? (:zookeeper-read-flow-conditions metrics)))
      (is (seq? (:zookeeper-read-lifecycles metrics)))
      (is (seq? (:zookeeper-read-messaging metrics)))
      (is (seq? (:zookeeper-read-job-scheduler metrics)))
      (is (seq? (:zookeeper-read-origin metrics)))
      (is (seq? (:zookeeper-write-messaging metrics)))
      (is (seq? (:zookeeper-write-job-scheduler metrics)))
      (is (seq? (:zookeeper-write-log-entry metrics))))

    (doseq [v-peer v-peers]
      (onyx.api/shutdown-peer v-peer))
    (onyx.api/shutdown-peer-group peer-group)
    (onyx.api/shutdown-env env))) 

