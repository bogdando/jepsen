(ns jepsen.noop-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [jepsen.noop :refer :all]
            [jepsen [core :as jepsen]
                    [report :as report]]))

; A test params
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

(def test-proc
  "A process/service name for targeted tests"
  (or (System/getenv "TESTPROC") "fooproc"))

(def test-node
  "A node name for targeted tests"
  (System/getenv "TESTNODE"))

; TODO(bogdando) add nodes filters by Fuel roles (hiera), e.g. computes,
; by a Pacemaker DC node, multistate master/slave resource status, or
; a Galera cluster prim node etc (see elasticsearch test's self-primaries)
(def nodes
  ; an arbitrary list of nodes under test, like node-1, .. node-999
  [:n1 :n2 :n3])

(defn run-set-test!
  "Runs a test around set creation and dumps some results to the report/ dir"
  [t]
  (let [test (jepsen/run! t)]
    (or (is (:valid? (:results test)))
        (println (:error (:results test))))
    (report/to (str "report/" (:name test) "/history.edn")
               (pprint (:history test)))
    (report/to (str "report/" (:name test) "/set.edn")
               (pprint (:set (:results test))))))

; Executable test cases
(deftest factors-netpart-test
  (run-set-test! (create-factors-netpart nodes
                                         factor-time factor-wait
                                         factor-duration)))

(deftest factors-crashstop-test
  (run-set-test! (create-factors-crashstop nodes
                                           factor-time factor-wait 1
                                           test-proc test-node)))

(deftest factors-freeze-test
  (run-set-test! (create-factors-freeze nodes
                                        factor-time factor-wait factor-duration
                                        test-proc test-node)))
