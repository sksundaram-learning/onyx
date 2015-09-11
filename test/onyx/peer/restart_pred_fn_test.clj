(ns onyx.peer.restart-pred-fn-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.extensions :as extensions]
            [onyx.test-helper :refer [load-config playback-log get-counts]]
            [onyx.api]))

(def n-messages 100)

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

(def batch-counter (atom 0))

(def startup-counter (atom 0))

(def check-restarted-calls
  {:lifecycle/before-task-start (fn [_ _ ]
                                  (swap! startup-counter inc)
                                  (when (= 2 @startup-counter)
                                    (>!! in-chan :done)
                                    (close! in-chan))
                                  {})
   :lifecycle/before-batch (fn [_ _]
                             (when (= (swap! batch-counter inc) 2)
                               (throw (ex-info "Oops, I died." {:restartable? true})))
                             {})})
(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(defn restartable? [e]
  (:restartable? (ex-data e)))

(deftest restart-pred-fn-test 
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
                  :onyx/fn :onyx.peer.restart-pred-fn-test/my-inc
                  :onyx/restart-pred-fn :onyx.peer.restart-pred-fn-test/restartable?
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
                     :lifecycle/calls :onyx.peer.restart-pred-fn-test/in-calls}
                    {:lifecycle/task :in
                     :lifecycle/calls :onyx.plugin.core-async/reader-calls}
                    {:lifecycle/task :inc
                     :lifecycle/calls :onyx.peer.restart-pred-fn-test/check-restarted-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.peer.restart-pred-fn-test/out-calls}
                    {:lifecycle/task :out
                     :lifecycle/calls :onyx.plugin.core-async/writer-calls}]
        v-peers (onyx.api/start-peers 3 peer-group)
        _ (onyx.api/submit-job
            peer-config
            {:catalog catalog
             :workflow workflow
             :lifecycles lifecycles
             :task-scheduler :onyx.task-scheduler/balanced})
        _ (doseq [n (range n-messages)]
            (>!! in-chan {:n n}))
        results (take-segments! out-chan)]

    (is (= @startup-counter 2))

    (doseq [v-peer v-peers]
      (onyx.api/shutdown-peer v-peer))
    (onyx.api/shutdown-peer-group peer-group)
    (onyx.api/shutdown-env env)))
