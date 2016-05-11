(defproject jepsen.percona_ocf_pcmk "0.1.0-SNAPSHOT"
  :description "Codership/Percona/MariaDB by Pacemaker OCF RA tests for Jepsen"
  :url "https://github.com/bogdando/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  ;NOTE(bogdando) the custom jepsen build below requires a local repo
  :plugins [[lein-localrepo "0.5.3"]]
  :local-repo "resources"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [jepsen "0.1.1-SNAPSHOT"]
                 [jepsen.galera "0.1.0-SNAPSHOT"]
                 [honeysql "0.6.1"]
                 [org.clojure/java.jdbc "0.4.1"]
                 [org.mariadb.jdbc/mariadb-java-client "1.2.0"]])
