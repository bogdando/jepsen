(ns jepsen.percona_ocf_pcmk-test
  (:require [clojure.test :refer :all]
            [jepsen.core :refer [run!]]
            [jepsen.percona_ocf_pcmk :refer :all]
            [jepsen.percona_ocf_pcmk.dirty-reads :as dirty-reads]))

;(deftest sets-test'
;  (is (:valid? (:results (run! (sets-test))))))

; the test for a single node for all writes and reads
(deftest bank-test-single
  (is (:valid? (:results (run! (bank-test 2 10 " FOR UPDATE" false first :serializable))))))

; the test for a "multi master" writes and reads
(deftest bank-test-multi
  (is (:valid? (:results (run! (bank-test 2 10 " FOR UPDATE" false rand-nth :serializable))))))

; the test for a lock-less SELECT and RR transactions, which is default mode
(deftest bank-test-multi-rr
  (is (:valid? (:results (run! (bank-test 2 10 "" false rand-nth :repeatable-read))))))

; the test against dirty reads and A5A read skews
(deftest dirty-reads-test
  (is (:valid? (:results (run! (dirty-reads/test- 4 rand-nth :serializable))))))

(deftest dirty-reads-test-rr
  (is (:valid? (:results (run! (dirty-reads/test- 4 rand-nth :repeatable-read))))))
