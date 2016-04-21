(ns jepsen.percona_ocf_pcmk-test
  (:require [clojure.test :refer :all]
            [jepsen.core :refer [run!]]
            [jepsen.percona_ocf_pcmk :refer :all]
            [jepsen.percona_ocf_pcmk.dirty-reads :as dirty-reads]))

;(deftest sets-test'
;  (is (:valid? (:results (run! (sets-test))))))

; the test for a single node for all writes and reads
(deftest bank-test-single
  (is (:valid? (:results (run! (bank-test 2 10 " FOR UPDATE" false first))))))

; the test for a "multi master" writes and reads
(deftest bank-test-multi
  (is (:valid? (:results (run! (bank-test 2 10 " FOR UPDATE" false rand-nth))))))

(deftest dirty-reads-test
  (is (:valid? (:results (run! (dirty-reads/test- 4))))))
