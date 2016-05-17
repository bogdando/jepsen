(ns jepsen.percona_ocf_pcmk.dirty-reads
  "Dirty read analysis for Mariadb Galera Cluster.

  In this test, writers compete to set every row in a table to some unique
  value. Concurrently, readers attempt to read every row. We're looking for
  casdes where a *failed* transaction's number was visible to some reader."
  (:require [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
             [core :as jepsen]
             [tests :as tests]
             [control :as c :refer [|]]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout meh]]
             [percona_ocf_pcmk :as percona]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]
            [slingshot.slingshot   :refer [try+]]
            [clojure.java.jdbc :as j]))


(defrecord Client [node n mode ti-level]
  client/Client
  (setup! [this test node]
    (j/with-db-connection [c (percona/conn-spec node)]
      ; Create table
      (j/execute! c ["create table if not exists dirty
                     (id      int not null primary key,
                      x       bigint not null)"])
      ; Create rows
      (dotimes [i n]
        (try
          (percona/with-txn-retries
            (Thread/sleep (rand-int 10))
            (j/insert! c :dirty {:id i, :x -1}))
          (catch java.sql.SQLIntegrityConstraintViolationException e nil))))

    (assoc this :node node))

  (invoke! [this test op]
    (let [fail (if(= :read (:f op))
                 :fail
                 :info)]
      (timeout 5000 (assoc op :type fail, :value :timed-out)
        (percona/with-error-handling op
          (percona/with-txn-aborts op
            (j/with-db-transaction [c (percona/conn-spec (mode (:nodes test)))
                                  :isolation ti-level]
              (try+
                (case (:f op)
                  :read (->> (j/query c ["select * from dirty"])
                             (mapv :x)
                             (assoc op :type :ok, :value))

                  :write (let [x (:value op)
                               order (shuffle (range n))]
                           (doseq [i order]
                             (j/query c ["select * from dirty where id = ?" i]))
                           (doseq [i order]
                             (j/update! c :dirty {:x x} ["id = ?" i]))
                           (assoc op :type :ok)))

              (catch java.net.SocketTimeoutException e
                (assoc op :type fail :value :timed-out))

              (catch Exception e
                    (assoc op :type fail :value (:cause e))))))))))

  (teardown! [_ test]))

(defn client
  [n mode ti-level]
  (Client. nil n mode ti-level))

(defn checker
  "We're looking for a failed transaction whose value became visible to some
  read."
  []
  (reify checker/Checker
    (check [this test model history _]
      (let [failed-writes (->> history
                               (r/filter op/fail?)
                               (r/filter #(= :write (:f %)))
                               (r/map :value)
                               (into (hash-set)))
            reads (->> history
                       (r/filter op/ok?)
                       (r/filter #(= :read (:f %)))
                       (r/map :value))
            inconsistent-reads (->> reads
                                    (r/filter (partial apply not=))
                                    (into []))
            filthy-reads (->> reads
                              (r/filter (partial some failed-writes))
                              (into []))]
        {:valid? (empty? filthy-reads)
         :inconsistent-reads inconsistent-reads
         :dirty-reads filthy-reads}))))

(def reads {:type :invoke, :f :read, :value nil})

(def writes (->> (range)
                 (map (partial array-map
                               :type :invoke,
                               :f :write,
                               :value))
                 gen/seq))

(defn test-
  [n mode ti-level]
  (percona/basic-test
    {:name "dirty reads"
     :concurrency 50
     :client (client n mode ti-level)
     :db percona/db
     :generator (gen/phases
                  (->> (gen/mix [reads writes])
                       (percona/with-nemesis)
                       (gen/time-limit 200))
                  (gen/log "waiting for quiescence")
                  (gen/sleep 180)
                  (gen/clients (gen/each (gen/once reads))))
     :nemesis (nemesis/partition-random-halves)
     ;:nemesis nemesis/noop
     :checker (checker/compose {:dirty-reads (checker)})}))
