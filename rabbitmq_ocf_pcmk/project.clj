(defproject jepsen.rabbitmq_ocf_pcmk "0.1.0-SNAPSHOT"
  :description "RabbitMQ by Pacemaker OCF RA tests for Jepsen"
  :url "https://github.com/bogdando/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  ;NOTE(bogdando) the custom jepsen build below requires a local repo
  :plugins [[lein-localrepo "0.5.3"]]
  :local-repo "resources"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.novemberain/langohr "3.5.1" ]
                 [slingshot "0.12.2"]
                 [jepsen "0.1.1-SNAPSHOT"]])
