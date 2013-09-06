;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(defproject containium "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Mozilla Public License 2.0"
            :url "http://mozilla.org/MPL/2.0/"}
  :dependencies [[boxure/clojure "1.5.1"]
                 [boxure "0.1.0-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.3"]
                 [jline "2.11"]
                 [ring/ring-core "1.2.0"]
                 [http-kit "2.1.10"]
                 [org.apache.httpcomponents/httpclient "4.2.3"]
                 [org.apache.cassandra/cassandra-all "1.2.8"]
                 [org.elasticsearch/elasticsearch "0.90.3"]
                 [org.scala-lang/scala-library "2.9.2"]
                 [kafka/core-kafka_2.9.2 "0.7.2"]
                 [com.taoensso/nippy "2.1.0"]
                 [org.clojure/core.cache "0.6.3"]
                 [boxure/shared_2.9.2 "0.1-SNAPSHOT"]]
  :exclusions [org.clojure/clojure]
  :java-source-paths ["src-java"]
  :main containium.core
  :global-vars {*warn-on-reflection* true})
