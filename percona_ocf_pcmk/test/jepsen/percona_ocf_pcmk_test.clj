(ns jepsen.percona_ocf_pcmk-test
  (:require [clojure.test :refer :all]
            [jepsen.core :refer [run!]]
            [jepsen.percona_ocf_pcmk :refer :all]
            [jepsen.percona.dirty-reads :as dirty-reads]))

;(deftest sets-test'
;  (is (:valid? (:results (run! (sets-test))))))

(deftest bank-test'
  (is (:valid? (:results (run! (bank-test 2 10 " FOR UPDATE" false))))))

;(deftest dirty-reads-test
;  (is (:valid? (:results (run! (dirty-reads/test- 4))))))
