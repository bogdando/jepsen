(ns jepsen.noop
  (:require [clojure.tools.logging :refer [debug info warn]]
            [clojure.java.io       :as io]
            [jepsen.core           :as core]
            [jepsen.util           :refer [meh]]
            [jepsen.codec          :as codec]
            [jepsen.core           :as core]
            [jepsen.control        :as c]
            [jepsen.control.util   :as cu]
            [jepsen.db             :as db]
            [slingshot.slingshot   :refer [try+]]))

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
