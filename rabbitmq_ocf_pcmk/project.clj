(defproject jepsen.rabbitmq_ocf_pcmk "0.1.0-SNAPSHOT"
  :description "RabbitMQ by Pacemaker OCF RA tests for Jepsen"
  :url "https://github.com/bogdando/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [jepsen "0.0.7"]
                 [com.novemberain/langohr "2.7.1" ]])
