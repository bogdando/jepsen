(ns jepsen.rabbitmq_ocf_pcmk-test
  (:use jepsen.rabbitmq_ocf_pcmk
        jepsen.core
        jepsen.tests
        clojure.test
        clojure.pprint)
  (:require [clojure.string   :as str]
            [jepsen.util      :as util]
            [jepsen.os.debian :as debian]
            [jepsen.checker   :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.model     :as model]
            [jepsen.generator :as gen]
            [jepsen.nemesis   :as nemesis]
            [jepsen.store     :as store]
            [jepsen.report    :as report]))

(deftest mutex-test
  (let [test (run!
               (assoc
                 noop-test
                 :name      "rabbitmq-mutex"
                 :db        db
                 :client    (mutex)
                 :checker   (checker/compose {:html   timeline/html
                                              :linear checker/linearizable})
                 :model     (model/mutex)
                 :nemesis   (nemesis/partition-random-halves)
                 :generator (gen/phases
                              (->> (gen/seq
                                     (cycle [{:type :invoke :f :acquire}
                                             {:type :invoke :f :release}]))
                                gen/each
                                (gen/delay 180)
                                (gen/nemesis
                                  (gen/seq
                                    (cycle [(gen/sleep 5)
                                            {:type :info :f :start}
                                            (gen/sleep 100)
                                            {:type :info :f :stop}])))
                                (gen/time-limit 500)))))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))

(deftest rabbit-test
  (let [test (run!
               (assoc
                 noop-test
                 :name       "rabbitmq-simple-partition"
                 :nodes     ["node-1.domain.tld" "node-2.domain.tld" "node-4.domain.tld" "node-5.domain.tld" "node-6.domain.tld"]
                 :db         db
                 :client     (queue-client)
                 :nemesis    (nemesis/partition-random-halves)
                 :model      (model/unordered-queue)
                 :checker    (checker/compose
                               {:queue       checker/queue
                                :total-queue checker/total-queue})
                 :ssh        {:username "root", :private-key-path "~/.ssh/id_rsa"}
                 :generator  (gen/phases
                               (->> (gen/queue)
                                    (gen/delay 1/10)
                                    (gen/nemesis
                                      (gen/seq
                                        (cycle [(gen/sleep 180)
                                                {:type :info :f :start}
                                                (gen/sleep 180)
                                                {:type :info :f :stop}])))
                                    (gen/time-limit 700))
                               (gen/nemesis
                                 (gen/once {:type :info, :f :stop}))
                               (gen/log "waiting for recovery")
                               (gen/sleep 120)
                               (gen/clients
                                 (gen/each
                                   (gen/once {:type :invoke
                                              :f    :drain}))))))]
    (is (:valid? (:results test)))))
