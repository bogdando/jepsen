(ns jepsen.rabbitmq_ocf_pcmk-test
  (:use jepsen.rabbitmq_ocf_pcmk
        jepsen.core
        jepsen.tests
        clojure.test
        clojure.pprint)
  (:require [clojure.string   :as str]
            [jepsen.util      :as util]
            [jepsen.os        :as os]
            [jepsen.control   :as control]
            [jepsen.tests     :as tst]
            [jepsen.checker   :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.model     :as model]
            [jepsen.generator :as gen]
            [jepsen.nemesis   :as nemesis]
            [jepsen.store     :as store]
            [jepsen.report    :as report]))

(def nodes
  "A list of cluster nodes under test"
  (try
    (map keyword
      (str/split
        (read-string (System/getenv "NODES")) #"\s"))
    (catch Exception e [:n1 :n2 :n3 :n4 :n5])))

(deftest mutex-test
  (let [os-startups  (atom {})                                                                                                                        
        os-teardowns (atom {})
        nonce        (rand-int Integer/MAX_VALUE)
        nonce-file   "/tmp/jepsen-test"
        ;override hardcoded control.net/hosts-map by the given list of nodes
        hosts-map    (into {} (map hash-map nodes (map name nodes)))
        test (run!
               (assoc
                 tst/noop-test
                 :name      "rabbitmq-mutex"
                 :nodes     nodes
                 :os (reify os/OS
                       (setup! [_ test node]
                         (swap! os-startups assoc node
                               (control/exec :hostname)))

                       (teardown! [_ test node]
                         (swap! os-teardowns assoc node
                               (control/exec :hostname))))
                 :db db
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
    (report/linearizability (:linear (:results test)))
    (is (apply =
               (str nonce)
               (->> test
                    :nodes
                    (map #(->> (store/path test (name %)
                                           (str/replace nonce-file #".+/" ""))
                               slurp
                               str/trim)))))
    ;reworked the original magic to fit the custom list of nodes undet test
    (is (= @os-startups @os-teardowns hosts-map))))

(deftest rabbit-test
  (let [os-startups  (atom {})                                                                                                                        
        os-teardowns (atom {})
        nonce        (rand-int Integer/MAX_VALUE)
        nonce-file   "/tmp/jepsen-test"
        ;override hardcoded control.net/hosts-map by the given list of nodes
        hosts-map    (into {} (map hash-map nodes (map name nodes)))
        test (run!
               (assoc
                 tst/noop-test
                 :name       "rabbitmq-simple-partition"
                 :nodes      nodes
                 :os (reify os/OS
                       (setup! [_ test node]
                         (swap! os-startups assoc node
                               (control/exec :hostname)))

                       (teardown! [_ test node]
                         (swap! os-teardowns assoc node
                               (control/exec :hostname))))                 
                 :db db
                 :client     (queue-client)
                 :nemesis    (nemesis/partition-random-halves)
                 :model      (model/unordered-queue)
                 :checker    (checker/compose
                               {:queue       checker/queue
                                :total-queue checker/total-queue})
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
    (is (:valid? (:results test)))
    (is (apply =
               (str nonce)
               (->> test
                    :nodes
                    (map #(->> (store/path test (name %)
                                           (str/replace nonce-file #".+/" ""))
                               slurp
                               str/trim)))))
    ;reworked the original magic to fit the custom list of nodes undet test
    (is (= @os-startups @os-teardowns hosts-map))))
