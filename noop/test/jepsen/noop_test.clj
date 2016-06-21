(ns jepsen.noop-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [jepsen.noop :refer :all]
            [jepsen [core :as jepsen]
                    [report :as report]
                    [tests :as tst]
                    [os :as os]
                    [db :as db]
                    [store :as store]
                    [control :as control]]))

; A test params
(def nodes-root-pass "r00tme")

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
; a Galera cluster prim node etc (see elasticsearch test's self-primaries).
; Make those to be read from a config file as an option.
(def nodes
  ; an arbitrary list of nodes under test, like node-1, .. node-999
  [:node-3.test.domain.local
   :node-2.test.domain.local
   :node-1.test.domain.local])

(defmacro with-pass [ & body ]
  `(binding [jepsen.control/*password* nodes-root-pass]
            [jepsen.control/*strict-host-key-checking* :no]
     ~@body))

(defn run-set-test!
  "Runs a test around set creation and dumps some results to the report/ dir"
  [t]
  (let [test (with-pass (jepsen/run! t))]
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

;NOTE(bogdando) reused it from jepsen.core ssh-test, although reworked hardcodes
(deftest ssh-test
  (let [os-startups  (atom {})
        os-teardowns (atom {})
        db-startups  (atom {})
        db-teardowns (atom {})
        db-primaries (atom [])
        nonce        (rand-int Integer/MAX_VALUE)
        nonce-file   "/tmp/jepsen-test"
        ;override hardcoded control.net/hosts-map by the given list of nodes
        hosts-map    (into {} (map hash-map nodes (map name nodes)))
        test (with-pass
                  (jepsen/run! (assoc tst/noop-test
                          :name      "ssh test"
                          :nodes nodes
                          :os (reify os/OS
                                (setup! [_ test node]
                                  (swap! os-startups assoc node
                                         (control/exec :hostname)))

                                (teardown! [_ test node]
                                  (swap! os-teardowns assoc node
                                         (control/exec :hostname))))

                          :db (reify db/DB
                                (setup! [_ test node]
                                  (swap! db-startups assoc node
                                         (control/exec :hostname))
                                  (control/exec :echo nonce :> nonce-file))

                                (teardown! [_ test node]
                                  (swap! db-teardowns assoc node
                                         (control/exec :hostname))
                                  (control/exec :rm nonce-file))

                                db/Primary
                                (setup-primary! [_ test node]
                                  (swap! db-primaries conj
                                         (control/exec :hostname)))

                                db/LogFiles
                                (log-files [_ test node]
                                  [nonce-file])))))]

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
    (is (= @os-startups @os-teardowns @db-startups @db-teardowns hosts-map))
    (is (= @db-primaries [(first (map hosts-map nodes))]))))
