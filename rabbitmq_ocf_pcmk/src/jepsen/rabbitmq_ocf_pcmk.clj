(ns jepsen.rabbitmq_ocf_pcmk
  (:require [clojure.tools.logging :refer [debug info warn]]
            [clojure.java.io       :as io]
            [jepsen.core           :as core]
            [jepsen.util           :refer [meh timeout log-op]]
            [jepsen.codec          :as codec]
            [jepsen.core           :as core]
            [jepsen.control        :as c]
            [jepsen.control.util   :as cu]
            [jepsen.client         :as client]
            [jepsen.db             :as db]
            [jepsen.generator      :as gen]
            [knossos.core          :as knossos]
            [knossos.op            :as op]
            [langohr.core          :as rmq]
            [langohr.channel       :as lch]
            [langohr.confirm       :as lco]
            [langohr.queue         :as lq]
            [langohr.exchange      :as le]
            [langohr.basic         :as lb]
            [slingshot.slingshot   :refer [try+]])
  (:import (com.rabbitmq.client AlreadyClosedException
                                ShutdownSignalException)))

(def db
  (reify db/DB
    (setup! [_ test node]
      (c/cd "/tmp"
                ; Ensure node is running
                (try (c/exec :rabbitmqctl :status)
                     (catch RuntimeException _
                     (info "Waiting rabbitmq")
                     (c/exec :rabbitmqctl :wait :/var/run/rabbitmq/pid)))

                ; Wait for everyone to start up
                (core/synchronize test)

                (info node "Rabbit ready")))

    (teardown! [_ test node]
      (c/su
        (info node "Nothing to do")))

    db/LogFiles
    (log-files [_ test node]
      ["/var/log/syslog"])))

(def queue "jepsen.queue")

(defn dequeue!
  "Given a channel and an operation, dequeues a value and returns the
  corresponding operation."
  [ch op]
  ; Rabbit+Langohr's auto-ack dynamics mean that even if we issue a dequeue req
  ; then crash, the message should be re-delivered and we can count this as a
  ; failure.
  (timeout 5000 (assoc op :type :fail :value :timeout)
           (let [[meta payload] (lb/get ch queue)
                 value          (codec/decode payload)]
             (if (nil? meta)
               (assoc op :type :fail :value :exhausted)
               (assoc op :type :ok :value value)))))

(defmacro with-ch
  "Opens a channel on 'conn for body, binds it to the provided symbol 'ch, and
  ensures the channel is closed after body returns."
  [[ch conn] & body]
  `(let [~ch (lch/open ~conn)]
     (try ~@body
          (finally
            (meh (rmq/close ~ch))))))

(defn InitQueue
  "(Re-)initialize a queue. A disposable cluster allows queues to be lost, it
  shall be recreated then"
  [conn]
  (with-ch [ch conn]
    ; Initialize queue
    (lq/declare ch queue
                  {:durable     false
                   :auto-delete false
                   :exclusive   false})))

(defrecord QueueClient [conn]
  client/Client
  (setup! [_ test node]
    (let [conn (rmq/connect
                 {:host (name node)
                  :username "nova" :password "B7A0SxdbEur6xNTwg1F5IjPx"
                  :automatically-recover true})]
      (InitQueue conn)

      ; Return client
      (QueueClient. conn)))

  (teardown! [_ test]
    ; Purge
    (meh (with-ch [ch conn]
           (lq/purge ch queue)))

    ; Close
    (meh (rmq/close conn)))

  (invoke! [this test op]
    (let [fail (if (or (= :drain (:f op))
                       (= :dequeue (:f op)))
                 :fail
                 :info)]
      (try+ (with-ch [ch conn]
        (case (:f op)
          :enqueue (do
                     (lco/select ch) ; Use confirmation tracking

                     ; Empty string is the default exhange
                     (lb/publish ch "" queue
                                 (codec/encode (:value op))
                                 {:content-type  "application/edn"
                                  :mandatory     false
                                  :persistent    false})

                     ; Block until message acknowledged
                     (if (lco/wait-for-confirms ch 5000)
                       (assoc op :type :ok)
                       (assoc op :type :fail)))

          :dequeue (dequeue! ch op)

          :drain   (do
                     ; Note that this does more dequeues than strictly necessary
                     ; owing to lazy sequence chunking.
                     (->> (repeat op)                  ; Explode drain into
                          (map #(assoc % :f :dequeue)) ; infinite dequeues, then
                          (map (partial dequeue! ch))  ; dequeue something
                          (take-while op/ok?)  ; as long as stuff arrives,
                          (interleave (repeat op))     ; interleave with invokes
                          (drop 1)                     ; except the initial one
                          (map (fn [completion]
                                 (log-op completion)
                                 (core/conj-op! test completion)))
                          dorun)
                     (assoc op :type :ok :value :exhausted))))

      ; Failure modes
      (catch java.net.SocketTimeoutException e
        (assoc op :type fail :value :timeout))

      (catch AlreadyClosedException e
        (meh (InitQueue conn))
        (assoc op :type fail :value :channel-closed))

      (catch ShutdownSignalException e
        (meh (InitQueue conn))
        (assoc op :type fail :value (:cause e)))

      (catch (and (instance? clojure.lang.ExceptionInfo %)) e
        (assoc op :type fail :value (:cause e)))

      (catch (and (:errorCode %) (:message %)) e
        (meh (InitQueue conn))
        (assoc op :type fail :value (:cause e)))

      (catch Exception e
        (meh (InitQueue conn))
        (assoc op :type fail :value (:cause e)))))))

(defn queue-client [] (QueueClient. nil))

; https://www.rabbitmq.com/blog/2014/02/19/distributed-semaphores-with-rabbitmq/
; enqueued is shared state for whether or not we enqueued the mutex record
; held is independent state to store the currently held message
(defrecord Semaphore [enqueued? conn ch tag]
  client/Client
  (setup! [_ test node]
    (let [conn (rmq/connect
                 {:host (name node)
                  :username "nova" :password "B7A0SxdbEur6xNTwg1F5IjPx"
                  :automatically-recover false})]
      (with-ch [ch conn]
        (lq/declare ch "jepsen.semaphore"
                    {:durable false
                     :auto-delete false
                     :exclusive false})

        ; Enqueue a single message
        (when (compare-and-set! enqueued? false true)
          (lco/select ch)
          (lq/purge ch "jepsen.semaphore")
          (lb/publish ch "" "jepsen.semaphore" (byte-array 0))
          (when-not (lco/wait-for-confirms ch 5000)
            (throw (RuntimeException.
                     "couldn't enqueue initial semaphore message!")))))

      (Semaphore. enqueued? conn (atom (lch/open conn)) (atom nil))))

  (teardown! [_ test]
    ; Purge
    (meh (timeout 5000 nil
                  (with-ch [ch conn]
                    (lq/purge ch "jepsen.semaphore"))))
    (meh (rmq/close @ch))
    (meh (rmq/close conn)))

  (invoke! [this test op]
    (case (:f op)
      :acquire (locking tag
                 (if @tag
                   (assoc op :type :fail :value :already-held)

                   (timeout 5000 (assoc op :type :fail :value :timeout)
                      (try+
                        ; Get a message but don't acknowledge it
                        (let [dtag (-> (lb/get @ch "jepsen.semaphore" false)
                                       first
                                       :delivery-tag)]
                          (if dtag
                            (do (reset! tag dtag)
                                (assoc op :type :ok :value dtag))
                            (assoc op :type :fail)))

                        (catch ShutdownSignalException e
                          (meh (reset! ch (lch/open conn)))
                          (assoc op :type :fail :value (:cause e)))

                        (catch AlreadyClosedException e
                          (meh (reset! ch (lch/open conn)))
                          (assoc op :type :fail :value :channel-closed))

                        (catch Exception e
                          (assoc op :type :info :value (:cause e)))))))

      :release (locking tag
                 (if-not @tag
                   (assoc op :type :fail :value :not-held)
                   (timeout 5000 (assoc op :type :ok :value :timeout)
                            (let [t @tag]
                              (reset! tag nil)
                              (try+
                                ; We're done now--we try to reject but it
                                ; doesn't matter if we succeed or not.
                                (lb/reject @ch t true)
                                (assoc op :type :ok)

                                (catch AlreadyClosedException e
                                  (meh (reset! ch (lch/open conn)))
                                  (assoc op :type :ok :value :channel-closed))

                                (catch ShutdownSignalException e
                                  (assoc op :type :ok :value (:cause e)))

                                (catch Exception e
                                  (assoc op :type :info :value (:cause e)))))))))))

(defn mutex [] (Semaphore. (atom false) nil nil nil))
