(defproject lolteambuilder "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jdbc "0.3.7"]
                 [clj-http "2.0.0"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [clj-postgresql "0.4.0"]
                 [cheshire "5.5.0"]
                 [ring/ring-core "1.4.0"]
                 [ring/ring-jetty-adapter "1.4.0"]
                 [compojure "1.4.0"]
                 [stencil "0.4.0"]
                 [org.clojure/algo.generic "0.1.2"]]
  :plugins [[lein-ring "0.9.6"]]
  :ring {:handler lolteambuilder.core/app}
  :main ^:skip-aot lolteambuilder.core
  :target-path "target/%s"
  :uberjar-name "standalone.jar"
  :profiles {:uberjar {:aot :all}})
