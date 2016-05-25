(ns jepsen.noop
  (:require [clojure.tools.logging :refer [debug info warn]]
            [jepsen.util           :refer [meh]]
            [slingshot.slingshot   :refer [try+]]
            [clojure.java.io       :as io]
            [clojure.string        :as str]
            [jepsen.checker        :as checker]
            [jepsen.client         :as client]
            [jepsen.core           :as core]
            [jepsen.control        :as c]
            [jepsen.control.util   :as cu]
            [jepsen.db             :as db]
            [jepsen.generator      :as gen]
            [jepsen.model          :as model]
            [jepsen.nemesis        :as nemesis]
            [jepsen.net            :as net]
            [jepsen.os             :as os]
            [jepsen.report         :as report]
            [jepsen.store          :as store]
            [jepsen.tests          :as tests]))

(def db
  ; set up / teardown / capture logs for something, if you want
  (reify db/DB
    (setup! [_ test node]
      (c/cd "/tmp"
        (let [url (str "https://something.io/afile")
              file (meh (cu/wget! url))]
          (info node "noop installing something" file)
          (c/exec :echo :dpkg :-i, :--force-confask :--force-confnew file))
          ; Ensure *something* is running
          (try+ (c/exec :echo :something :status)
               (catch RuntimeException _
               (info "Waiting for something")
               (c/exec :echo :something :wait)))

          ; Wait for everyone to start up
          (core/synchronize test)

          (info node "Noop is ready")))

    (teardown! [_ test node]
      (c/su
        (info node "Nothing to do")))

    db/LogFiles
    (log-files [_ test node]
      ["/var/log/syslog"])))

; Definitions of factors start/stop loops, targets, checks
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
  "A noop checker of a history. Enable perf maybe"
  (checker/compose {;:perf   (checker/perf)
                    :linear checker/unbridled-optimism}))

(defn targeter
  "Generate a target to a node, either random or a given"
  [node]
  (if (nil? node)
    #(rand-nth %)
    #(some #{(keyword node)} %)))

; Generator of test cases
(defn a-test
  "Defaults for testing"
  [name nodes factor-time factor-wait factor-duration opts]
  (merge tests/noop-test
         {:name      (str "factor " name)
          :os        os/noop
          :db        db
          :client    client/noop
          :model     model/noop
          :nodes     nodes
          :check     check
          :generator (factor factor-wait factor-duration factor-time)}
         opts))

(defn create-test
  "A generic create test for nodes"
  [name nodes factor-time factor-wait factor-duration opts]
  (a-test (str "create " name) nodes factor-time factor-wait factor-duration opts))

; Test cases to apply factors
(defn create-factors-netpart
  "Split nodes into random halved network partitions"
  [nodes factor-time factor-wait factor-duration]
  (create-test "net-part" nodes factor-time factor-wait factor-duration
               {:nemesis   (nemesis/partition-random-halves)}))

(defn create-factors-crashstop
  "Send SIGKILL for a given TESTPROC executed on a random or given
  TESTNODE, then restart maybe via the OS service CP"
  [nodes factor-time factor-wait factor-duration test-proc test-node]
  (create-test "crash-stop" nodes factor-time factor-wait factor-duration
               {:nemesis (nemesis/node-start-stopper
                            (targeter test-node)
                            (fn start [test node]
                              (meh (c/su (c/exec :killall :-9 test-proc)))
                              [:killed test-proc])
                            (fn stop [test node]
                              (info node (str "starting " test-proc))
                              (meh (c/su (c/exec :service test-proc :restart)))
                              [:restarted-maybe test-proc]))}))

(defn create-factors-freeze
  "Send SIGSTOP for a given TESTPROC executed on a random or given
  TESTNODE, then wait for the factor duration and send SIGCONT"
  [nodes factor-time factor-wait factor-duration test-proc test-node]
  (create-test "freeze" nodes factor-time factor-wait factor-duration
               {:nemesis (nemesis/hammer-time (targeter test-node)
                                              test-proc)}))
