(ns jepsen.noop-test
  (:use jepsen.noop
        jepsen.core
        jepsen.tests
        clojure.test
        clojure.pprint)
  (:require [jepsen.os        :as os]
            [jepsen.db        :as db]
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

(deftest factors-test
  (let [test (run!
               (assoc
                 noop-test
                 ; an arbitrary list of nodes, like node-1, .. node-999
                 :nodes     [:n1 :n2 :n3]
                 :name      "nemesis"
                 :os        os/noop
                 ;:db        db/noop
                 :db        db
                 :client    client/noop
                 :model     model/noop
                 :checker   (checker/compose {;:html   timeline/html
                                              ;:perf   (checker/perf)
                                              :linear checker/unbridled-optimism})
                 ; pick a mode you want?
                 :net       net/iptables
                 ; pick a mode you want
                 ;:nemesis   nemesis/noop
                 :nemesis   (nemesis/partition-random-halves)
                 ; create a generator for factor(s).start / stop
                 ;:generator gen/void
                 :generator (gen/phases
                              (->> (gen/nemesis
                                     (gen/seq
                                       (cycle [(gen/sleep 5)
                                               {:type :info :f :start}
                                               (gen/sleep 20)
                                               {:type :info :f :stop}])))
                                   (gen/time-limit 100)))))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))
