(defproject jepsen.percona_ocf_pcmk "0.1.0-SNAPSHOT"
  :description "Codership/Percona/MariaDB by Pacemaker OCF RA tests for Jepsen"
  :url "https://github.com/bogdando/jepsen"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  ;NOTE(bogdando) the custom jepsen build below requires a local repo
  :plugins [[lein-localrepo "0.5.3"]]
  :local-repo "resources"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 ;FIXME(bogdando) use v0.0.8, if my patches accepted
                 ;[jepsen "0.0.7-SNAPSHOT"]
                 [jepsen "0.1.0-SNAPSHOT"]
                 [jepsen.galera "0.1.0-SNAPSHOT"]
                 [honeysql "0.6.1"]
                 [org.clojure/java.jdbc "0.4.1"]
                 [org.mariadb.jdbc/mariadb-java-client "1.2.0"]
                 ;FIXME(bogdando) remove these, when switched to v0.0.8
                 [org.clojure/clojure "1.7.0"]
                 [org.clojure/data.fressian "0.2.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [clj-time "0.6.0"]
                 [knossos "0.2.4"]
                 [clj-ssh "0.5.11"]
                 [gnuplot "0.1.1"]
                 [hiccup "1.0.5"]
                 [org.clojars.achim/multiset "0.1.0"]
                 [byte-streams "0.1.4"]])
