(ns jepsen.noop-test
  (:use jepsen.noop
        jepsen.core
        jepsen.tests
        clojure.test
        clojure.pprint)
  (:require [clojure.string   :as str]
            [clojure.tools.logging  :refer [info]]
            [jepsen.util            :refer [meh]]
            [jepsen.os        :as os]
            [jepsen.db        :as db]
            [jepsen.control   :as c]
            [jepsen.client    :as client]
            [jepsen.util      :as util]
            [jepsen.checker   :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.model     :as model]
            [jepsen.generator :as gen]
            [jepsen.nemesis   :as nemesis]
            [jepsen.net       :as net]
            [jepsen.store     :as store]
            [jepsen.report    :as report]))

(deftest factors-netpart-test
  "For a 3 min long, generate random halves split network partitions with a
  random start delay of 5 to 20 seconds and duration of 10 to 60 seconds"
  (let [test (run!
               (assoc
                 noop-test
                 ; an arbitrary list of nodes, like node-1, .. node-999
                 :nodes     [:n1 :n2 :n3]
                 :name      "nemesis"
                 :os        os/noop
                 :db        db
                 :client    client/noop
                 :model     model/noop
                 :checker   (checker/compose
                              {;:html   timeline/html
                               ;:perf   (checker/perf)
                               :linear checker/unbridled-optimism})
                 ; pick modes you want
                 :net       net/iptables
                 :nemesis   (nemesis/partition-random-halves)
                 ; create a generator for a factor.start / stop
                 :generator (gen/phases
                              (->> (gen/nemesis
                                     (gen/seq
                                       (cycle [(gen/sleep (+ 5 (rand-int 15)))
                                               {:type :info :f :start}
                                               (gen/sleep (+ 10 (rand-int 50)))
                                               {:type :info :f :stop}])))
                                   (gen/time-limit 180))
                              (gen/nemesis
                                (gen/once {:type :info, :f :stop}))
                              (gen/log "Stopped"))))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))

(deftest factors-crashstop-test
  "For a 3 min long, generate killall -9 events for a given TESTPROC env var
  executed on a given TESTNODE, or pick a random node, then restart (maybe)
  via the service CP, repeat with a random delay of 5 to 20 seconds"
  (defn targeter
    [node]
    (if (nil? node)
      #(rand-nth %)
      #(some #{(keyword node)} %)))

  (let [proc (or (System/getenv "TESTPROC") "killme")
        target (targeter (System/getenv "TESTNODE"))
        test (run!
               (assoc
                 noop-test
                 ; an arbitrary list of nodes, like node-1, .. node-999
                 :nodes     [:n1 :n2 :n3]
                 :name      "nemesis"
                 :os        os/noop
                 :db        db
                 :client    client/noop
                 :model     model/noop
                 :checker   (checker/compose
                              {;:html   timeline/html
                               ;:perf   (checker/perf)
                               :linear checker/unbridled-optimism})
                 ; pick modes you want
                 :nemesis   (nemesis/node-start-stopper
                              target
                              (fn start [test node]
                                (meh (c/su (c/exec :killall :-9 proc)))
                                [:killed proc])
                              (fn stop [test node]
                                (info node (str "starting " proc))
                                (meh (c/su (c/exec :service proc :restart)))
                                [:restarted-maybe proc]))
                 ; create a generator for a factor.start only
                 :generator (gen/phases
                              (->> (gen/nemesis
                                     (gen/seq
                                       (cycle [(gen/sleep (+ 5 (rand-int 15)))
                                               {:type :info :f :start}
                                               (gen/sleep 1)
                                               {:type :info :f :stop}])))
                                   (gen/time-limit 180))
                              (gen/log "Stopped"))))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))
