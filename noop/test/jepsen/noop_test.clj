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

(def factor-time
  "How long to apply a factor, default 180s"
  (try
    (read-string (System/getenv "FTIME"))
    (catch Exception e 180)))

(def factor-wait
  "How long to wait for a first start (and before the next start)
  of the factor being applied, default from 5 to 20s"
  (try
    (read-string (System/getenv "FWAIT"))
    (catch Exception e (+ 5 (rand-int 15)))))

(def factor-duration
  "Duration of the factor being applied before stopped within an
  iteration, default from 10 to 60s"
  (try
    (read-string (System/getenv "FDURATION"))
    (catch Exception e (+ 10 (rand-int 50)))))

(def nodes
  ; an arbitrary list of nodes under test, like node-1, .. node-999
  [:n1 :n2 :n3])

(defn factor
  "Generator for a factor.start / factor.stop events"
  [factor-wait factor-duration factor-time]
  (gen/phases
     (->> (gen/nemesis
            (gen/seq
              (cycle [(gen/sleep factor-wait)
                      {:type :info :f :start}
                      (gen/sleep factor-duration)
                      {:type :info :f :stop}])))
          (gen/time-limit factor-time))
     (gen/log "Stopped")))

(def check
  "A noop checker of a history. Enable timeline/perf maybe?"
  (checker/compose {;:html   timeline/html
                    ;:perf   (checker/perf)
                    :linear checker/unbridled-optimism}))

(deftest factors-netpart-test
  "Split nodes into random halved network partitions"
  (let [test (run!
               (assoc
                 noop-test
                 :nodes     nodes
                 :name      "nemesis"
                 :os        os/noop
                 :db        db
                 :client    client/noop
                 :model     model/noop
                 :checker   check
                 :net       net/iptables
                 :nemesis   (nemesis/partition-random-halves)
                 :generator (factor factor-wait factor-duration factor-time)))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))

(defn targeter
  "Generate a target to a node, either random or a given"
  ;TODO(bogdando) add targets by Fuel roles (hiera), like controller or compute,
  ;targets by a Pacemaker DC node, or a Galera prim node, or a Pacemaker
  ;multistate master/slave resource status (see elasticsearch's self-primaries)
  [node]
  (if (nil? node)
    #(rand-nth %)
    #(some #{(keyword node)} %)))

(deftest factors-crashstop-test
  "Send SIGKILL for a given TESTPROC executed on a random or given
  TESTNODE, then restart maybe via the OS service CP"

  (let [proc (or (System/getenv "TESTPROC") "killme")
        target (targeter (System/getenv "TESTNODE"))
        test (run!
               (assoc
                 noop-test
                 :nodes     nodes
                 :name      "nemesis"
                 :os        os/noop
                 :db        db
                 :client    client/noop
                 :model     model/noop
                 :checker   check
                 :nemesis   (nemesis/node-start-stopper
                              target
                              (fn start [test node]
                                (meh (c/su (c/exec :killall :-9 proc)))
                                [:killed proc])
                              (fn stop [test node]
                                (info node (str "starting " proc))
                                (meh (c/su (c/exec :service proc :restart)))
                                [:restarted-maybe proc]))
                 :generator (factor factor-wait 1 factor-time)))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))

(deftest factors-freeze-test
  "Send SIGSTOP for a given TESTPROC executed on a random or given
  TESTNODE, then wait for the factor duration and send SIGCONT"

  (let [proc (or (System/getenv "TESTPROC") "freezeme")
        target (targeter (System/getenv "TESTNODE"))
        test (run!
               (assoc
                 noop-test
                 :nodes     nodes
                 :name      "nemesis"
                 :os        os/noop
                 :db        db
                 :client    client/noop
                 :model     model/noop
                 :checker   check
                 :nemesis   (nemesis/hammer-time target proc)
                 :generator (factor factor-wait factor-duration factor-time)))]
    (is (:valid? (:results test)))
    (report/linearizability (:linear (:results test)))))
