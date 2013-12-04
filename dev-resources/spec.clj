;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

;;;; Development settings.

{:http-kit {:port 8080
            :max-body 52428800} ; 50 mb
 :netty {:port 8090
         :zero-copy true
         :max-http-chunk-length 1073741824 ; 1 gb
         :max-channel-memory-size 1048576 ; 1 mb
         :max-total-memory-size 1048576} ; 1 mb
 :jetty9 {:port 8100
          :join? false}
 :cassandra {:config-file "cassandra.yaml"}
 :alia {:contact-points ["localhost"]
        :port 9042}
 :session-store {:ttl 1}
 :kafka {:server {:port 9090
                  :brokerid 1
                  :log.dir "target/kafka-log"
                  :enable.zookeeper false}
         :producer {:broker.list "1:localhost:9090"
                    ;; :serializer.class "nl.storm.MessagePackVOSerializer"
                    }}
 :elastic {}
 :modules {:resolve-dependencies true
           :isolates ["containium.*"
                      "org\\.httpkit.*"
                      "taoensso\\.nippy.*"
                      "ring.*"]}
 :repl {:port 13337}
 :fs {:deployments "dev-resources/deployments"}}
