(ns jepsen.percona_ocf_pcmk
  "Tests for Percona XtraDB"
  (:require [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
             [core :as jepsen]
             [db :as db]
             [tests :as tests]
             [control :as c :refer [|]]
             [checker :as checker]
             [nemesis :as nemesis]
             [generator :as gen]
             [util :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [clojure.java.jdbc :as j]
            [slingshot.slingshot   :refer [try+]]
            [honeysql [core :as sql]
                      [helpers :as h]]))

(def log-files
  ["/var/log/syslog"
   "/var/log/mysql.log"
   "/var/log/mysql.err"
   "/var/log/mysql/error.log"
   "/var/lib/mysql/queries.log"])

(def dir "/var/lib/mysql")

(defn eval!
  "Evals a mysql string from the command line."
  [s]
  (meh (c/exec :mysql :-u "root" "--password=root" :-e s)))

(defn conn-spec
  "jdbc connection spec for a node."
  [node]
  {:classname   "org.mariadb.jdbc.Driver"
   :subprotocol "mariadb"
   :subname     (str "//" (name node) ":3306/jepsen")
   :user        "root"
   :password    "root"})

(defn setup-db!
  "Adds a jepsen database to the cluster."
  [node]
  (eval! "create database if not exists jepsen;")
  (eval! (str "GRANT ALL PRIVILEGES ON jepsen.* "
              "TO 'root'@'%' IDENTIFIED BY 'root';")))

(def db
  "Sets up and tears down Galera."
  (reify db/DB
    (setup! [_ test node]
      (jepsen/synchronize test)
      (setup-db! node))

    (teardown! [_ test node]
      (c/su
        (eval! "drop database if exists jepsen;")))

    db/LogFiles
    (log-files [_ test node] log-files)))

(def rollback-msg
  "mariadb drivers have a few exception classes that use this message"
  "Deadlock found when trying to get lock; try restarting transaction")

(defmacro capture-txn-abort
  "Converts aborted transactions to an ::abort keyword"
  [& body]
  `(try ~@body
        (catch java.sql.SQLTransactionRollbackException e#
          (if (= (.getMessage e#) rollback-msg)
            ::abort
            (throw e#)))
        (catch java.sql.BatchUpdateException e#
          (if (= (.getMessage e#) rollback-msg)
            ::abort
            (throw e#)))))

(defmacro with-txn-retries
  "Retries body on rollbacks."
  [& body]
  `(loop []
     (let [res# (capture-txn-abort ~@body)]
       (if (= ::abort res#)
         (recur)
         res#))))

(defmacro with-txn-aborts
  "Aborts body on rollbacks."
  [op & body]
  `(let [res# (capture-txn-abort ~@body)]
     (if (= ::abort res#)
       (assoc ~op :type :fail)
       res#)))

(defmacro with-error-handling
  "Common error handling for Galera errors"
  [op & body]
  `(try ~@body
        (catch java.sql.SQLNonTransientConnectionException e#
          (condp = (.getMessage e#)
            "WSREP has not yet prepared node for application use"
            (assoc ~op :type :fail, :value (.getMessage e#))

            (throw e#)))))

(defmacro with-txn
  "Executes body in a transaction, with a timeout, automatically retrying
  conflicts and handling common errors."
  [ti-level op [c node] & body]
  `(timeout 5000 nil
           (with-error-handling ~op
             (with-txn-retries
               (j/with-db-transaction [~c (conn-spec ~node)
                                       :isolation ~ti-level]
                 (j/execute! ~c ["start transaction with consistent snapshot"])
                 ~@body)))))

(defn basic-test
  [opts]
  (merge tests/noop-test
         {:name (str "percona " (:name opts))
          :nemesis (nemesis/partition-random-halves)}
         (dissoc opts :name)))

(defn with-nemesis
  "Wraps a client generator in a nemesis that induces failures and eventually
  stops."
  [client]
  (gen/phases
    (gen/phases
      (->> client
           (gen/nemesis
             (gen/seq (cycle [(gen/sleep (rand-int 20))
                              {:type :info, :f :start}
                              (gen/sleep (rand-int 400))
                              {:type :info, :f :stop}])))
           (gen/time-limit 1600))
      (gen/nemesis (gen/once {:type :info, :f :stop}))
      (gen/sleep 5))))

(defrecord BankClient [node n starting-balance lock-type in-place? mode ti-level]
  client/Client
  (setup! [this test node]
    (j/with-db-connection [c (conn-spec (first (:nodes test)))]
      ; Create table
      (j/execute! c ["create table if not exists accounts
                     (id      int not null primary key,
                     balance bigint not null)"])
      ; Create initial accts
      (dotimes [i n]
        (try
          (with-txn-retries
            (j/insert! c :accounts {:id i, :balance starting-balance}))
          (catch java.sql.SQLIntegrityConstraintViolationException e nil))))

    (assoc this :node node))

  (invoke! [this test op]
    (let [fail (if (= :read (:f op))
                 :fail
                 :info)]
      (with-txn ti-level op [c (mode (:nodes test))]
        (try+
          (case (:f op)
            :read (->> (j/query c [(str "select * from accounts" lock-type)])
                       (mapv :balance)
                       (assoc op :type :ok, :value))

            :transfer
            (let [{:keys [from to amount]} (:value op)
                  b1 (-> c
                         (j/query [(str "select * from accounts where id = ?"
                                        lock-type)
                                   from]
                           :row-fn :balance)
                         first
                         (- amount))
                  b2 (-> c
                         (j/query [(str "select * from accounts where id = ?"
                                        lock-type)
                                   to]
                           :row-fn :balance)
                         first
                         (+ amount))]
              (cond (neg? b1)
                    (assoc op :type :fail, :value [:negative from b1])

                    (neg? b2)
                    (assoc op :type :fail, :value [:negative to b2])

                    true
                    (if in-place?
                      (do (j/execute! c ["update accounts set balance = balance - ? where id = ?" amount from])
                          (j/execute! c ["update accounts set balance = balance + ? where id = ?" amount to])
                          (assoc op :type :ok))
                      (do (j/update! c :accounts {:balance b1} ["id = ?" from])
                          (j/update! c :accounts {:balance b2} ["id = ?" to])
                          (assoc op :type :ok))))))

        (catch java.net.SocketTimeoutException e
          (assoc op :type fail :value :timed-out))

        (catch Exception e
          (assoc op :type fail :value (:cause e)))))))

  (teardown! [_ test]))

(defn bank-client
  "Simulates bank account transfers between n accounts, each starting with
  starting-balance"
  [n starting-balance lock-type in-place? mode ti-level]
  (BankClient. nil n starting-balance lock-type in-place? mode ti-level))

(defn bank-read
  "Reads the current state of all accounts without any synchronization."
  [_ _]
  {:type :invoke, :f :read})

(defn bank-transfer
  "Transfers a random amount between two randomly selected accounts."
  [test process]
  (let [n (-> test :client :n)]
    {:type  :invoke
     :f     :transfer
     :value {:from   (rand-int n)
             :to     (rand-int n)
             :amount (rand-int 5)}}))

(def bank-diff-transfer
  "Like transfer, but only transfers between *different* accounts."
  (gen/filter (fn [op] (not= (-> op :value :from)
                             (-> op :value :to)))
              bank-transfer))

(defn bank-checker
  "Balances must all be non-negative and sum to the model's total."
  []
  (reify checker/Checker
    (check [this test model history _]
      (let [bad-reads (->> history
                           (r/filter op/ok?)
                           (r/filter #(= :read (:f %)))
                           (r/map (fn [op]
                                  (let [balances (:value op)]
                                    (cond (not= (:n model) (count balances))
                                          {:type :wrong-n
                                           :expected (:n model)
                                           :found    (count balances)
                                           :op       op}

                                         (not= (:total model)
                                               (reduce + balances))
                                         {:type :wrong-total
                                          :expected (:total model)
                                          :found    (reduce + balances)
                                          :op       op}))))
                           (r/filter identity)
                           (into []))]
        {:valid? (empty? bad-reads)
         :bad-reads bad-reads}))))

(defn bank-test
  [n initial-balance lock-type in-place? mode ti-level]
  (basic-test
    {:name "bank"
     :db db
     :concurrency 20
     :model  {:n n :total (* n initial-balance)}
     :client (bank-client n initial-balance lock-type in-place? mode ti-level)
     :generator (gen/phases
                  (->> (gen/mix [bank-read bank-diff-transfer])
                       (gen/stagger 1/10)
                       (with-nemesis)
                       (gen/time-limit 3600))
                  (gen/log "waiting for quiescence")
                  (gen/sleep 180)
                  (gen/clients (gen/each (gen/once bank-read))))
     :nemesis (nemesis/partition-random-halves)
     ;:nemesis nemesis/noop
     :checker (checker/compose {:bank (bank-checker)})}))
